(ns tolgraven.component
  (:require
    [clojure.string :as string]
    [clojure.walk :as walk]
    [tolgraven.render-context :as context]
    [tolgraven.component.registry :as registry]
    [tolgraven.schema.declarations :as schemas]
    [tolgraven.validation.runtime :as validation]
    [tolgraven.component.instrumentation :as instrumentation]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [tolgraven.component.motion :as motion]
    [tolgraven.component.font :as font]
    [tolgraven.component.restore :as restore]
    [tolgraven.component.visibility :as visibility]
    [tolgraven.component.persistent-state :as state-store]
    [tolgraven.component.loading :as loading]
    [tolgraven.component.storage :as storage]
    [tolgraven.components.error :as error]
    [tolgraven.util :as util]
    [tolgraven.component.data :as data]
    [tolgraven.component.sources]
    [tolgraven.macros :refer-macros [defc]]))

(def state state-store/state)
(def <sub state-store/<sub)
(def >reset state-store/>reset)
(def >update state-store/>update)
(def path-of state-store/path-of)
(def dump-state! state-store/dump!)
(defn dump-content! [] (storage/flush! :content))

(defn resolve-view
  "Resolve exported Vars at render time; Reagent 2 defc values are descriptors,
   not callable functions. Keep module Vars for development hot reloading."
  [view]
  (if (var? view) @view view))

(defonce *features (atom {}))

(defn register-feature!
  [id lifecycle]
  (swap! *features assoc id lifecycle))

(defn feature
  [id]
  (get @*features id))

(defn- spec-for [spec? args]
  (when (and spec? (map? (first args))) (first args)))

(defn- classes [value]
  (cond
    (nil? value) []
    (sequential? value) (mapcat classes value)
    (keyword? value) [(name value)]
    :else [(str value)]))

(defn- root-props
  "Only DOM roots accept DOM props. Never insert a spec into child arguments."
  [form spec mounted? capture-ref]
  (if (and (map? spec) (vector? form)
           (or (string? (first form))
               (and (keyword? (first form))
                    (not (contains? #{:<> :> :r> :f>} (first form))))))
    (let [[tag maybe-attrs & children] form
          attrs? (map? maybe-attrs)
          attrs (if attrs? maybe-attrs {})
          props (:props spec)
          merged (merge attrs props)
          class-name (string/join " " (mapcat classes
                                      [(:class attrs) (:class props) (:classes spec)
                                       (when mounted? "TOL_mounted")]))
          merged (cond-> merged
                   (seq class-name) (assoc :class class-name)
                   (or (:style attrs) (:style props))
                   (assoc :style (merge (:style attrs) (:style props))))
          merged (if (:capture-root? spec)
                   (assoc merged :ref (capture-ref (:ref attrs) (:ref props)))
                   ;; Preserve both refs even when props supplies its own ref.
                   (if (and (:ref attrs) (:ref props))
                     (assoc merged :ref (capture-ref (:ref attrs) (:ref props)))
                     merged))]
      (with-meta (into [tag merged] (if attrs? children (rest form))) (meta form)))
    form))

(defonce page-root-context (rf/create-context nil))

(defn- set-ref! [ref element]
  (cond (fn? ref) (ref element)
        ref (set! (.-current ^js ref) element)))

(def component-spec registry/component-spec)
(def register-component! registry/register-component!)
(def definition registry/definition)

(defn dependencies [definition args]
  (registry/validate-args! definition args)
  (let [declared (get-in definition [:options :depends])
        resolved (if (fn? declared) (apply declared args) declared)
        spec (spec-for (not= false (get-in definition [:options :spec])) args)]
    (vec (distinct (concat resolved (:depends spec))))))

(instrumentation/register-dependencies-resolver! dependencies)

(defn preload!
  "Start a component's declared dependencies without constructing or mounting it."
  [component & args]
  (if-let [definition (component-spec component)]
    (data/ensure-all! (dependencies definition args))
    (js/Promise.resolve nil)))

(defn prefetch! [component & args]
  (-> (apply preload! component args) (.catch (fn [_] nil))))

(defn- render-body! [*render args]
  (loop []
    (let [form (apply @*render args)]
      (if (fn? form) (do (reset! *render form) (recur)) form))))

(defn- resolved-features [definition]
  (mapv (fn [[id config]]
          [id config (or (feature id)
                         (throw (ex-info "Component feature is not registered" {:feature id})))])
        (:features definition)))

(defn- current-spec [definition args]
  (or (spec-for (not= false (get-in definition [:options :spec])) args) {}))

(defn- feature-config [id config spec args]
  (let [value (get spec id config)]
    (if (fn? value) (apply value args) value)))

(defn- decorate [form features spec mounted? capture-ref]
  (reduce (fn [form [id config implementation]]
            (cond
              (= :props id) (root-props form spec mounted? capture-ref)
              (:transform implementation) ((:transform implementation) form spec config)
              :else form))
          form features))

(defn- exit-config [component]
  (some #(when (= :exit (first %)) (second %)) (:features (component-spec component))))

(defn- use-lifecycle! [definition args features *element]
  (let [[mounted? set-mounted!] (rf/use-state false)
        *active (rf/use-ref {})
        *latest (rf/use-ref args)
        this (r/current-component)
        cleanup! (fn [id]
                   (when-let [{:keys [implementation state]} (get (.-current *active) id)]
                     (set! (.-current *active) (dissoc (.-current *active) id))
                     (when state (when-let [unmount (:unmount implementation)] (unmount state)))))
        lifecycle? (some #(= :lifecycle (first %)) features)]
    (set! (.-current *latest) args)
    (rf/use-layout-effect
     (fn []
       (let [spec (current-spec definition args)]
         (doseq [[id default implementation] features :when (:setup implementation)]
           (let [config (feature-config id default spec args)
                 old (get (.-current *active) id)]
             (when (or (not= config (:config old))
                       (not= @*element (:element old))
                       (not= implementation (:implementation old)))
               (cleanup! id)
               (when (and config @*element)
                 (let [state ((:setup implementation) config)]
                   (set! (.-current *active)
                         (assoc (.-current *active) id {:implementation implementation :state state
                                                       :config config :element @*element}))
                   (when state (when-let [mount (:mount implementation)] (mount state @*element)))))))))
       js/undefined))
    (rf/use-layout-effect
     (fn []
       (set-mounted! true)
       (when lifecycle?
         (when-let [init (:init (current-spec definition (.-current *latest)))] (init this)))
       (fn []
         (try (doseq [id (reverse (map first features))] (cleanup! id))
              (finally
                (when lifecycle?
                  (when-let [exit (:exit (current-spec definition (.-current *latest)))] (exit this)))))))
     #js [])
    mounted?))

(defn- render-function [definition args presence state-key]
  (registry/validate-args! definition args)
  (let [*instance (rf/use-ref nil)]
    (when-not (.-current *instance)
      (let [*element (atom nil)]
        (set! (.-current *instance)
              {:render (atom (binding [state-store/*component* definition state-store/*args* args state-store/*react-key* state-key]
                               (apply (:make-render definition) args)))
               :element *element
               :capture-ref (memoize (fn [base caller]
                                      (fn [element]
                                        (reset! *element element)
                                        (doseq [ref (distinct [base caller])]
                                          (set-ref! ref element)))))})))
    (let [{*render :render *element :element capture-ref :capture-ref} (.-current *instance)
          features (resolved-features definition)
          ids (set (map first features))
          spec (current-spec definition args)
          lifecycle? (some #(or (= :lifecycle (first %)) (:setup (nth % 2))) features)
          mounted? (when lifecycle? (use-lifecycle! definition args features *element))
          form (decorate
                 (binding [state-store/*component* definition state-store/*args* args state-store/*react-key* state-key]
                   (instrumentation/render! definition args #(render-body! *render args)))
                 (if (and (ids :container) (not (false? (:container spec))))
                   (remove #(= :props (first %)) features) features)
                 spec mounted? capture-ref)
          form (if lifecycle?
                 (root-props form {:capture-root? true} false capture-ref) form)
          options (into {} (map (fn [[id default _]] [id (feature-config id default spec args)]) features))
          form (if (some ids [:appear :seen :exit]) (motion/use-motion form options presence) form)
          form (if (ids :presence) (motion/use-presence form exit-config) form)
          font-options (:font options)
          _ (when (and (ids :font) font-options)
              (validation/check! "component font" font/options-schema font-options))
          form (if (ids :font) (font/use-font form font-options) form)]
      (instrumentation/instrument definition form args state-key))))

(r/defc <function-body> [definition args presence state-key]
  (render-function definition args presence state-key))

(def <boundary>
  (r/create-class
   {:display-name "Component error boundary"
    :get-initial-state (fn [_] #js {:error nil :stack nil :attempt 0 :resetKey nil})
    :get-derived-state-from-props
    (fn [{:keys [reset-key]} ^js state]
      (when (not= reset-key (.-resetKey state))
        #js {:error nil :stack nil :resetKey reset-key
             :attempt (if (.-error state) (inc (.-attempt state)) (.-attempt state))}))
    :get-derived-state-from-error (fn [error] #js {:error error})
    :should-component-update (fn [_ _ _] true)
    :component-did-catch
    (fn [this exception info]
      (let [[_ {:keys [ns-name component-name]}] (r/argv this)]
        (.setState this #js {:stack (.-componentStack ^js info)})
        (util/log :error (str "Component " ns-name "/" component-name)
                  (or (ex-message exception) (str exception)))))
    :render
    (fn [^js this]
      (let [[_ {:keys [ns-name component-name]} form] (r/argv this)
            state (.-state this)]
        (r/as-element
         (if-let [exception (.-error state)]
           [error/<failure> ns-name component-name {:error exception :stack (str (.-stack exception) "\n" (.-stack state))}
            (fn [] (.setState this (fn [previous _]
                                    #js {:error nil :stack nil :attempt (inc (.-attempt previous))})))]
           (with-meta [:<> form] {:key (.-attempt state)})))))}))

(r/defc <container>
  "Optional layout surface. Callers may select a view or Hiccup template.
   Custom views accept a spec with :props and content forms. :container/content
   places content inside a template; without a slot it is appended at the root."
  [defaults spec content]
  (let [navigation-props (rf/use-context page-root-context)
        *capture-ref (rf/use-ref nil)
        supplied (get spec :container defaults)
        _ (when (contains? spec :container)
            (validation/check! "component container" schemas/container supplied))
        options (merge (when (map? defaults) defaults)
                       (cond (map? supplied) supplied
                             (vector? supplied) {:form supplied}
                             :else {}))]
    (when-not (.-current *capture-ref)
      (set! (.-current *capture-ref)
            (memoize (fn [base caller]
                       (fn [element]
                         (doseq [ref (distinct [base caller])] (set-ref! ref element)))))))
    (if (false? supplied)
      content
      (let [children [:r> (rf/context-provider page-root-context) #js {:value nil} content]
            template (or (:form options) [(or (:tag options) :div)])
            slotted? (some #{:container/content} (tree-seq coll? seq template))
            form (if slotted?
                   (walk/postwalk #(if (= :container/content %) children %) template)
                   (conj template children))
            capture-ref (.-current *capture-ref)
            ;; Keep the declared default attrs even when the caller selects a new
            ;; template/view; page roots still need their shared layout class.
            attrs-form (root-props [:div (:props defaults)]
                                   {:props (when (and (contains? spec :container) (map? supplied))
                                             (:props supplied))}
                                   false capture-ref)
            attrs-form (root-props attrs-form spec false capture-ref)
            attrs-form (root-props attrs-form {:props navigation-props} false capture-ref)
            attrs (second attrs-form)]
        (cond
          (:view options) [(resolve-view (:view options)) {:props attrs} children]
          (motion/dom-root? form) (root-props form {:props attrs} false capture-ref)
          :else
          (let [[view maybe-spec & forms] form
                spec? (map? maybe-spec)
                container-spec (if spec? maybe-spec {})
                merged (root-props [:div (:props container-spec)] {:props attrs} false capture-ref)]
            (into [(resolve-view view) (assoc container-spec :props (second merged))]
                  (if spec? forms (rest form)))))))))


(defn loading-view [options]
  [loading/<placeholder> options])

(defn- loading-form [definition args]
  (let [options (merge (:options definition) (current-spec definition args))
        view (:loading options)]
    (cond
      (vector? view) view
      view (into [(resolve-view view)] args)
      (= :rendered (:loading-prefab options))
      (let [sample (:loading-args options)
            sample (if (fn? sample) (apply sample args) sample)
            args (cond (map? sample) [sample] (sequential? sample) sample :else args)]
        [loading/<rendered> {:form [<function-body> definition args nil nil]}])
      :else (loading-view options))))

(declare <loading-reveal>)

(r/defc <data-body> [definition args form]
  (let [skeleton? (rf/use-context loading/render-context)
        resources (dependencies definition args)]
    ;; Deliberately before mount: requests are shared and not owned by a React
    ;; instance, so speculative/abandoned renders neither duplicate nor leak them.
    (when-not (or skeleton? context/*server?*) (data/prefetch! resources))
    (let [status (if skeleton? :ready (data/state resources))
          _ (restore/use-readiness! (not= :loading status))
          rendered? (= :rendered (:loading-prefab (merge (:options definition)
                                                       (current-spec definition args))))]
      (cond
        (= :error status)
        [error/<failure> (:ns definition) (:name definition)
         {:title "This component's data could not be loaded"
          :message "Check your connection and try loading this content again."
          :error (data/failure resources)}
         #(data/retry-background! resources)]
        (and rendered? (not skeleton?))
        [<loading-reveal> {:ready? (= :ready status) :form form
                          :skeleton (when-not (= :ready status) (loading-form definition args))}]
        (= :ready status) form
        :else (loading-form definition args)))))

(defn- current-argv []
  ;; Reagent 2 defc stores (subvec hiccup 1) on its function render state.
  ;; subvec drops metadata, but retains the original Hiccup vector as its backing
  ;; vector. Read that metadata before introducing any defc wrappers. Class-based
  ;; runtime components still expose the original vector through r/argv.
  (rf/component-argv))

(defn render-component [definition raw-args]
  (let [state-key (some-> (current-argv) meta :key)
        presence (when (motion/presence? (last raw-args)) (last raw-args))
        args (if presence (butlast raw-args) raw-args)
        features (resolved-features definition)
        data? (or (get-in definition [:options :depends]) (some #(= :data (first %)) features))
        wrapped? (some #(get-in % [2 :wrap]) features)]
    ;; Merge-only features run in the original defc function, adding neither
    ;; React component nodes nor DOM wrappers. Wrappers are explicit opt-ins.
    (if-not (or data? wrapped?)
      (render-function definition args presence state-key)
      (let [body [<function-body> definition args presence state-key]
            body (if data? [<data-body> definition args body] body)]
        (reduce (fn [form [id config feature]]
                  (if-let [wrap (:wrap feature)]
                    (wrap form definition
                          (if (= id :container)
                            {:options config :spec (current-spec definition args)}
                            config))
                    form))
                body (reverse features))))))

(register-feature! :data {})
(register-feature! :props {})
(register-feature! :lifecycle {})
(register-feature! :appear {})
(register-feature! :seen {})
(register-feature! :font {})
(register-feature! :on-seen visibility/feature)
(register-feature! :exit {})
(register-feature! :presence {})
(register-feature! :container
                   {:wrap (fn [form _ {:keys [options spec]}]
                            [<container> options spec form])})
(register-feature! :error-boundary
                   {:wrap (fn [form definition _]
                            [<boundary> {:ns-name (:ns definition) :component-name (:name definition)
                                         :reset-key (when (get-in definition [:options :page])
                                                      (select-keys @(rf/subscribe [:common/route])
                                                                   [:path :query-params]))} form])})

(r/defc <dynamic-component> [definition args]
  (render-component definition args))

(defn create-component
  "Runtime constructor; static definitions should use defc for the inline path."
  [ns-name component-name options make-render]
  (let [definition (definition ns-name component-name options make-render)
        component (fn [& args]
                    (with-meta [<dynamic-component> definition args]
                      (meta (current-argv))))]
    (register-component! component definition)))

(defc <skeleton-layer>
  {:features [[:exit {:class "component-loading-reveal__exit" :timeout-ms 500}]]}
  [{:keys [form]}]
  [:div.component-loading-reveal__skeleton form])

(defc <loading-reveal>
  {:features [:presence]}
  [{:keys [ready? form skeleton]}]
  [:div.component-loading-reveal
   ;; Both layers occupy one CSS grid cell. Removing the skeleton does not move
   ;; the live content; presence owns its final unmount.
   (when ready? form)
   (when-not ready?
     ^{:key :skeleton} [<skeleton-layer> {:form skeleton}])])

(r/defc <prefetch>
  "An optional viewport sentinel for any source; fetching needs no component DOM."
  [resources]
  (r/with-let [*observer (atom nil)
               *resources (atom resources)
               attach! (fn [element]
                         (when-let [observer @*observer] (.disconnect observer))
                         (reset! *observer nil)
                         (when element
                           (if (exists? js/IntersectionObserver)
                             (let [observer (js/IntersectionObserver.
                                             (fn [entries observer]
                                               (when (some #(.-isIntersecting %) (array-seq entries))
                                                 (.disconnect observer)
                                                 (data/prefetch! @*resources)))
                                             #js {:rootMargin "800px 0px" :threshold 0})]
                               (reset! *observer observer)
                               (.observe observer element))
                             (data/prefetch! @*resources))))]
    (reset! *resources resources)
    [:div {:key resources :aria-hidden true :style {:height "1px" :margin-bottom "-1px" :pointer-events "none"}
           :ref attach!}]
    (finally (when-let [observer @*observer] (.disconnect observer)))))
