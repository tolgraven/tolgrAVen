(ns tolgraven.component
  (:require
    [clojure.string :as string]
    [clojure.walk :as walk]
    [tolgraven.render-context :as context]
    [tolgraven.component.registry :as registry]
    [tolgraven.component.ref :as refs]
    [tolgraven.component.features :as feature-options]
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

(def feature-schema
  [:map
   [:?root? :boolean]
   [:?transform [:fn fn?]]
   [:?wrap [:fn fn?]]
   [:?setup [:fn fn?]]
   [:?mount [:fn fn?]]
   [:?reconfigure [:fn fn?]]
   [:?unmount [:fn fn?]]
   [:?render [:fn fn?]]])

(def feature-render-schema
  [:map
   [:form :any]
   [:?commit! [:?fn fn?]]])

(defonce *features (atom {}))

(defn register-feature!
  [id lifecycle]
  (validation/check! "component feature" [:tuple :keyword feature-schema] [id lifecycle])
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

(defn- current-spec [definition args]
  (or (spec-for (not= false (get-in definition [:options :spec])) args) {}))

(defn- resolved-features [definition args]
  (let [options (feature-options/resolve-options (:features definition)
                                                (current-spec definition args) args)
        ;; A page's recovery boundary is a required contract, not an optional
        ;; behavior that an individual use may discard.
        options' (if (get-in definition [:options :page])
                  (let [position (first (keep-indexed #(when (= :error-boundary (first %2)) %1)
                                                      (:features definition)))]
                    (if (some #(= :error-boundary (first %)) options)
                      options
                      (let [position' (min (or position 0) (count options))]
                        (into (conj (subvec options 0 position') [:error-boundary true])
                              (subvec options position')))))
                  options)]
    (mapv (fn [[id config]]
            [id config (or (feature id)
                           (throw (ex-info "Component feature is not registered" {:feature id})))])
          options')))

(defn- decorate [form features spec mounted? capture-ref]
  (reduce (fn [form [id config implementation]]
            (cond
              (= :props id) (root-props form spec mounted? capture-ref)
              (:transform implementation) ((:transform implementation) form spec config)
              :else form)) form features))

(defn- exit-config [form]
  (when (vector? form)
    (when-let [definition (component-spec (first form))]
      (let [args (vec (rest form))
            args' (if (motion/presence? (last args)) (pop args) args)
            spec (current-spec definition args')
            defaults (filterv #(= :exit (first %)) (:features definition))
            spec' (assoc spec :features
                        (filterv #(= :exit (first %)) (feature-options/entries (:features spec))))]
        (some #(when (= :exit (first %)) (second %))
              (feature-options/resolve-options defaults spec' args'))))))

(defonce ^:private motion-owner (js-obj))
(defonce ^:private lifecycle-owner (js-obj))

(def lifecycle-feature
  {:root? false
   :setup (fn [options] (atom options))
   :reconfigure (fn [state options] (reset! state options) state)
   :mount (fn [state _] (when-let [init (:init @state)] (init (:component @state))))
   :unmount (fn [state] (when-let [exit (:exit @state)] (exit (:component @state))))})

(defn- use-lifecycle! [owners instance commits shape refresh!]
  (let [*active (:active instance)
        *element (:element instance)
        cleanup! (fn [id]
                   (when-let [{:keys [implementation state]} (get @*active id)]
                     (swap! *active dissoc id)
                     (when (some? state)
                       (when-let [unmount (:unmount implementation)] (unmount state)))))]
    (rf/use-layout-effect
      (fn []
        (fn []
          ;; Additions and removals may happen after mount. Always release the
          ;; current owners, never a feature list captured by the first render.
          (let [*error (atom nil)]
            (doseq [id (reverse @(:order instance))]
              (try (cleanup! id)
                   (catch :default error
                     (when-not @*error (reset! *error error)))))
            (when @*error (throw @*error))))) [])
    (rf/use-layout-effect
      (fn []
        (let [ids (into #{} (map first) owners)
              *changed? (atom false)]
          (doseq [id (reverse @(:order instance)) :when (not (ids id))]
            (cleanup! id)
            (reset! *changed? true))
          (doseq [[id config implementation] owners :when (:setup implementation)]
            (let [old (get @*active id)
                  element @*element
                  eligible? (or (false? (:root? implementation)) element)]
              (when (or (not= config (:config old))
                        (not= element (:element old))
                        (not= implementation (:implementation old)))
                (if (and old eligible? (:reconfigure implementation)
                         (= element (:element old)) (= implementation (:implementation old)))
                  (let [state ((:reconfigure implementation) (:state old) config)]
                    (swap! *active assoc id (assoc old :config config :state state))
                    (when-not (identical? state (:state old)) (reset! *changed? true)))
                  (do
                    (when (or old eligible?) (reset! *changed? true))
                    (cleanup! id)
                    (when eligible?
                      (let [state ((:setup implementation) config)]
                        (swap! *active assoc id {:implementation implementation
                                                :state state
                                                :config config
                                                :element element})
                        (swap! (:order instance) #(conj (vec (remove #{id} %)) id))
                        (when (some? state)
                          (when-let [mount (:mount implementation)] (mount state element))))))))))
          (reset! (:order instance) (into [] (comp (map first) (filter #(contains? @*active %))) owners))
          ;; Render callbacks describe work for the exact owner state used by
          ;; that render. A replaced/disposed owner cannot commit stale work.
          (doseq [[id state commit!] commits
                  :when (identical? state (get-in @*active [id :state]))]
            (commit! @*element))
          (reset! (:shape instance) shape)
          (when @*changed? (refresh! inc)))))))

(defn- render-function [definition args presence state-key supplied-features]
  (registry/validate-args! definition args)
  (let [*instance (rf/use-ref nil)
        [version refresh!] (rf/use-state 0)
        hydrating? (rf/use-context restore/hydrating-context)]
    (when-not (.-current *instance)
      (let [*element (atom nil)]
        (set! (.-current *instance)
              {:render (atom (binding [state-store/*component* definition
                                      state-store/*args* args
                                      state-store/*react-key* state-key]
                               (apply (:make-render definition) args)))
               :element *element
               :active (atom {})
               :order (atom [])
               :shape (atom nil)
               :capture-ref (memoize (fn [base caller]
                                      (refs/compose
                                        (fn [element] (reset! *element element) nil)
                                        base caller)))})))
    (let [{*render :render *element :element capture-ref :capture-ref :as instance} (.-current *instance)
          features (or supplied-features (resolved-features definition args))
          ids (set (map first features))
          spec (current-spec definition args)
          options (into {} (map (fn [[id config _]] [id config])) features)
          _ (when (ids :font)
              (validation/check! "component font" font/options-schema (:font options)))
          appearance (or (:seen options) (:appear options))
          remember-key (when (map? appearance) (:remember-key appearance))
          remembered? (when remember-key @(rf/subscribe [:state [:motion-seen remember-key]]))
          motion? (or (some ids [:appear :seen :exit]) (= :exiting (:phase presence)))
          raw-form (binding [state-store/*component* definition
                             state-store/*args* args
                             state-store/*react-key* state-key]
                     (instrumentation/render! definition args #(render-body! *render args)))
          owners (cond-> (vec (filter #(and (:setup (nth % 2))
                                               (or raw-form (and (not= :presence (first %))
                                                                 (false? (:root? (nth % 2)))))) features))
                   (ids :lifecycle)
                   (conj [lifecycle-owner {:init (:init spec), :exit (:exit spec), :component (r/current-component)}
                          lifecycle-feature])
                   (and motion? raw-form)
                   (conj [motion-owner (assoc options :presence presence :hydrating? hydrating?
                                        :server? context/*server?* :remembered? remembered?)
                          motion/motion-feature]))
          mounted? (pos? version)
          form (decorate raw-form
                         (if (ids :container) (remove #(= :props (first %)) features) features)
                         spec (and (ids :lifecycle) mounted?) capture-ref)
          rendered (reduce
                     (fn [{:keys [form commits]} [id config implementation]]
                       (if-let [render (:render implementation)]
                         (let [state (get-in @(:active instance) [id :state])
                               result (render form config state)
                               _ (validation/check! "component feature render" feature-render-schema result)
                               {rendered-form :form commit! :commit!} result]
                           {:form rendered-form
                            :commits (cond-> commits commit! (conj [id state commit!]))})
                         {:form form, :commits commits}))
                     {:form form, :commits []}
                     (cond-> (vec (filter #(and (:render (nth % 2))
                                               (not (#{:font :presence} (first %)))) features))
                       (and motion? raw-form) (conj [motion-owner (second (last owners)) motion/motion-feature])
                       (ids :presence) (conj [:presence exit-config motion/presence-feature])
                       (ids :font) (conj [:font (:font options) font/feature])))
          ;; Rendering precedence matches existing native-root motion, then
          ;; presence retention, then the font text surface.
          form' (:form rendered)
          root? (some #(and (:setup (nth % 2)) (not (false? (:root? (nth % 2))))) owners)
          form'' (if root? (root-props form' {:capture-root? true} false capture-ref) form')
          shape {:features (mapv (fn [[id config _]] [id config]) features)
                 :raw (when (vector? raw-form) (first raw-form))
                 :root (when (vector? form'') (first form''))}
          previous @(:shape instance)]
      (when (and previous (not= (:features previous) (:features shape))
                 (= (:raw previous) (:raw shape)) (not= (:root previous) (:root shape)))
        (throw (ex-info "Changing a feature's root structure requires an intentional Hiccup key/remount"
                        {:component (:name definition)})))
      ;; Commit closures capture this render's work; an abandoned render cannot
      ;; overwrite a mutable pending-work slot shared with the committed tree.
      (use-lifecycle! owners instance (:commits rendered) shape refresh!)
      (instrumentation/instrument definition form'' args state-key))))

(r/defc <function-body> [definition args presence state-key features]
  (render-function definition args presence state-key features))

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
            (fn [] (.setState this (fn [^js previous _]
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
            (memoize refs/compose)))
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
            attrs-form' (root-props attrs-form spec false capture-ref)
            attrs-form'' (root-props attrs-form' {:props navigation-props} false capture-ref)
            attrs (second attrs-form'')]
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
            sample' (if (fn? sample) (apply sample args) sample)
            args' (cond (map? sample') [sample'] (sequential? sample') sample' :else args)]
        [loading/<rendered> {:form [<function-body> definition args' nil nil nil]}])
      :else (loading-view options))))

(declare <loading-reveal>)

(r/defc <data-body> [definition args form]
  (let [skeleton? (rf/use-context loading/render-context)
        resources (dependencies definition args)]
    (let [status (if skeleton? :ready @(rf/subscribe [:component-data/status resources]))
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
  (let [*composition (rf/use-ref nil)
        state-key (some-> (current-argv) meta :key)
        presence (when (motion/presence? (last raw-args)) (last raw-args))
        args (if presence (butlast raw-args) raw-args)
        features (resolved-features definition args)
        data? (boolean (or (get-in definition [:options :depends])
                           (some #(= :data (first %)) features)))
        wrappers (filterv #(get-in % [2 :wrap]) features)
        composition {:data? data?, :wrappers (mapv first wrappers)}]
    (when-not (.-current *composition) (set! (.-current *composition) composition))
    (when (not= composition (.-current *composition))
      (throw (ex-info "Changing structural component features requires an intentional Hiccup key/remount"
                      {:component (:name definition)})))
    (if-not (or data? (seq wrappers))
      (render-function definition args presence state-key features)
      (let [body [<function-body> definition args presence state-key features]
            body' (if data? [<data-body> definition args body] body)]
        (reduce (fn [form [id config implementation]]
                  ((:wrap implementation) form definition
                   (if (= id :container)
                     (let [defaults (:container-defaults (meta config))
                           spec (dissoc (current-spec definition args) :container)]
                       {:options (or defaults config)
                        :spec (cond-> spec defaults (assoc :container config))})
                     config))) body' (reverse wrappers))))))

(register-feature! :data {})
(register-feature! :props {})
(register-feature! :lifecycle {})
(register-feature! :appear {})
(register-feature! :seen {})
(register-feature! :font font/feature)
(register-feature! :on-seen visibility/feature)
(register-feature! :exit {})
(register-feature! :presence motion/presence-feature)
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

(defc <prefetch>
  "Optional source sentinel; shared visibility owns acquisition and cleanup."
  {:features [[:on-seen (fn [resources]
                         {:event [:component-data/load resources]
                          :once? true
                          :threshold 0
                          :root-margin "800px 0px"})]]}
  [resources]
  [:div {:aria-hidden true
         :style {:height "0.0625rem"
                 :margin-bottom "-0.0625rem"
                 :pointer-events "none"}}])
