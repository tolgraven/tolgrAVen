(ns tolgraven.component
  (:require [clojure.string :as string]
            [tolgraven.render-context :as context]
            [reagent.core :as r]
            [react :as react]
            [tolgraven.component.motion :as motion]
            [tolgraven.component.persistent-state :as state-store]
            [tolgraven.component.loading :as loading]
            [tolgraven.component.storage :as storage]
            [tolgraven.components.error :as error]
            [tolgraven.util :as util]
            [tolgraven.component.data :as data]
            [tolgraven.component.sources]))

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

(defn- set-ref! [ref element]
  (cond (fn? ref) (ref element)
        ref (set! (.-current ^js ref) element)))

(defonce ^:private *definitions (js/WeakMap.))

(defn component-spec [component]
  (.get *definitions (if (var? component) @component component)))

(defn register-component! [component definition]
  (.set *definitions component definition)
  component)

(defn definition [ns-name component-name options make-render]
  (let [features (->> (:features options)
                      (map #(if (keyword? %) [% true] %))
                      (remove #(false? (second %)))
                      vec)]
    {:ns ns-name :name component-name :options options :features features
     :make-render make-render}))

(defn dependencies [definition args]
  (let [declared (get-in definition [:options :depends])
        resolved (if (fn? declared) (apply declared args) declared)
        spec (spec-for (not= false (get-in definition [:options :spec])) args)]
    (vec (distinct (concat resolved (:depends spec))))))

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

(defn- feature-config [id config spec]
  (get spec id config))

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
  (let [[mounted? set-mounted!] (react/useState false)
        *active (react/useRef {})
        *latest (react/useRef args)
        this (r/current-component)
        cleanup! (fn [id]
                   (when-let [{:keys [implementation state]} (get (.-current *active) id)]
                     (set! (.-current *active) (dissoc (.-current *active) id))
                     (when state (when-let [unmount (:unmount implementation)] (unmount state)))))
        lifecycle? (some #(= :lifecycle (first %)) features)]
    (set! (.-current *latest) args)
    (react/useLayoutEffect
     (fn []
       (let [spec (current-spec definition args)]
         (doseq [[id default implementation] features :when (:setup implementation)]
           (let [config (feature-config id default spec)
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
    (react/useLayoutEffect
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
  (let [*instance (react/useRef nil)]
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
          form (decorate (binding [state-store/*component* definition state-store/*args* args state-store/*react-key* state-key] (render-body! *render args)) features spec mounted? capture-ref)
          form (if lifecycle?
                 (root-props form {:capture-root? true} false capture-ref) form)
          options (into {} (map (fn [[id default _]] [id (feature-config id default spec)]) features))
          form (if (some ids [:appear :seen :exit]) (motion/use-motion form options presence) form)
          form (if (ids :presence) (motion/use-presence form exit-config) form)]
      form)))

(r/defc <function-body> [definition args presence state-key]
  (render-function definition args presence state-key))

(def <boundary>
  (r/create-class
   {:display-name "Component error boundary"
    :get-initial-state (fn [_] #js {:error nil :stack nil :attempt 0})
    :get-derived-state-from-error (fn [error] #js {:error error})
    :should-component-update (fn [_ _ _] true)
    :component-did-catch
    (fn [this exception info]
      (let [[_ ns-name component-name] (r/argv this)]
        (.setState this #js {:stack (.-componentStack ^js info)})
        (util/log :error (str "Component " ns-name "/" component-name)
                  (or (ex-message exception) (str exception)))))
    :render
    (fn [this]
      (let [[_ ns-name component-name form] (r/argv this)
            state (.-state this)]
        (r/as-element
         (if-let [exception (.-error state)]
           [error/<failure> ns-name component-name {:error exception :stack (.-stack state)}
            (fn [] (.setState this (fn [previous _]
                                    #js {:error nil :stack nil :attempt (inc (.-attempt previous))})))]
           (with-meta [:<> form] {:key (.-attempt state)})))))}))

(r/defc <data-body> [definition args form]
  (let [resources (dependencies definition args)]
    ;; Deliberately before mount: requests are shared and not owned by a React
    ;; instance, so speculative/abandoned renders neither duplicate nor leak them.
    (when-not context/*server?* (data/prefetch! resources))
    (case (data/state resources)
      :ready form
      :error [error/<failure> (:ns definition) (:name definition)
              {:title "This component's data could not be loaded"
               :message "Check your connection and try loading this content again."
               :error (data/failure resources)}
              #(data/retry-background! resources)]
      (let [view (get-in definition [:options :loading])]
        (cond (fn? view) (apply view args) view view :else [loading/<spinner>])))))

(defn- current-argv []
  ;; Reagent 2 defc stores (subvec hiccup 1) on its function render state.
  ;; subvec drops metadata, but retains the original Hiccup vector as its backing
  ;; vector. Read that metadata before introducing any defc wrappers. Class-based
  ;; runtime components still expose the original vector through r/argv.
  (when-let [instance (r/current-component)]
    (if-let [argv (.-argv ^clj instance)]
      (if (instance? cljs.core/Subvec argv) (.-v ^cljs.core/Subvec argv) argv)
      (r/argv instance))))

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
        (reduce (fn [form [_ config feature]]
                  (if-let [wrap (:wrap feature)] (wrap form definition config) form))
                body (reverse features))))))

(register-feature! :data {})
(register-feature! :props {})
(register-feature! :lifecycle {})
(register-feature! :appear {})
(register-feature! :seen {})
(register-feature! :exit {})
(register-feature! :presence {})
(register-feature! :error-boundary
                   {:wrap (fn [form definition _]
                            [<boundary> (:ns definition) (:name definition) form])})

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
