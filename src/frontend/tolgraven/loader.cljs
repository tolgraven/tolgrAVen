(ns tolgraven.loader
  (:require
    [tolgraven.component.registry]
    [tolgraven.validation.runtime :as validation]
    [tolgraven.loader.code :as module-code]
    [tolgraven.loader.styles :as styles]
    [tolgraven.loader.activation :as activation]
    [tolgraven.component.hydration :as hydration]
    [tolgraven.react :as rf]
    [tolgraven.render-context :as context]
    [tolgraven.component.restore :as restore]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.component :as component]
    [tolgraven.component.loading :as loading]
    [tolgraven.component.data :as data]
    [tolgraven.components.error :as error]
    [tolgraven.service-status :as status]
    [reagent.ratom :as ratom]
    [shadow.lazy :as lazy])
  (:require-macros
    [tolgraven.macros :as m]))

(def modules module-code/modules)

(m/defc <default-missing>
  [& args]
  [:div (pr-str args)])

(defonce *loads (atom {}))
(defonce *code-loads (atom {}))
(defonce *installed (atom #{}))
(defonce ^:private *code-listeners (atom {}))
(def retry-delay-ms 3000)

(defn- listen-code! [module started!]
  ;; These callbacks belong to mounted Suspense boundaries, not application state.
  ;; Acquisition releases their promise gates even if React is retaining an older
  ;; tree during an ancestor transition; no effect waits on that transition.
  (swap! *code-listeners update module (fnil conj #{}) started!)
  (when (get @*code-loads module) (started!))
  #(swap! *code-listeners
          (fn [listeners]
            (let [remaining (disj (get listeners module) started!)]
              (if (seq remaining)
                (assoc listeners module remaining)
                (dissoc listeners module))))))

(defn- retry-later! [attempt!]
  (-> (js/Promise. (fn [resolve _] (js/setTimeout resolve retry-delay-ms)))
      (.then (fn [_] (attempt!)))))

(defn- acquire-attempt! [module]
  (let [css (styles/acquire! module)
        code (try
               (if-let [loadable (get modules module)]
                 (js/Promise.resolve (if (lazy/ready? loadable) @loadable (lazy/load loadable)))
                 (js/Promise.reject (ex-info "Unknown module" {:module module})))
               (catch :default error (js/Promise.reject error)))]
    (-> (js/Promise.all #js [code css])
        (.then (fn [results]
                 (let [spec (aget results 0)]
                   (validation/module! spec)
                   (-> (js/Promise.resolve (when-let [install! (:install spec)] (install!)))
                       (.then (fn [_]
                                (swap! *installed conj module)
                                (rf/dispatch [:loader/code-ready module])
                                spec)))))))))

(defn acquire-code!
  "Share parallel CSS/Shadow acquisition, silently retrying once after a delay.
   Publish readiness only after both assets and the install hook complete."
  [module]
  (or (get @*code-loads module)
      (let [promise (-> (acquire-attempt! module)
                        (.catch (fn [_] (retry-later! #(acquire-attempt! module))))
                        (.catch (fn [error]
                                  (swap! *code-loads dissoc module)
                                  (throw error))))]
        (swap! *code-loads assoc module promise)
        (doseq [started! (get @*code-listeners module)] (started!))
        promise)))

(defn ready? [module]
  (when-let [loadable (get modules module)]
    (and (lazy/ready? loadable)
         (styles/ready? module)
         (or (nil? (:install @loadable)) (contains? @*installed module)))))

(defn- prepare-data! [resources]
  ;; A new module load/navigation is an explicit retry opportunity. Rendering
  ;; components still retains errors until their Retry data action is used.
  (let [failed (set (filter #(= :error (:status (data/snapshot %))) resources))]
    (when (seq failed) (data/invalidate! failed))
    (data/ensure-all! resources)))

(defn- prepare-view! [spec view args]
  (if-let [definition (component/component-spec (get-in spec [:view (or view :view)]))]
    (prepare-data! (component/dependencies definition args))
    (js/Promise.resolve nil)))

(defn load!
  "Return a promise for an initialized module. Concurrent callers share one load.
   Hooks run per caller; module initialization runs once, including bundled modules."
  [{:keys [module view init-evt pre-fn post-fn args route]}]
  (if-let [loadable (get modules module)]
    (do
      (when init-evt (rf/dispatch init-evt))
      (when pre-fn (apply pre-fn args))
      (-> (or (get @*loads module)
               (let [known-data (prepare-data! (get content-contract/module-dependencies module []))
                     code (acquire-code! module)
                     promise (-> (js/Promise.all #js [code known-data])
                                 (.then (fn [loaded]
                                          (let [spec (aget loaded 0)]
                                            (-> (js/Promise.all
                                                 #js [(prepare-data! (:depends spec))
                                                      (prepare-view! spec view args)])
                                                (.then (fn []
                                                         (rf/dispatch [:scope/init module args])
                                                         (-> (js/Promise.resolve
                                                              (when-let [init (:init spec)]
                                                                (apply init args)))
                                                             (.then (fn [_] spec)))))))))
                                 (.catch (fn [error]
                                           (swap! *loads dissoc module)
                                           (throw error))))]
                 (swap! *loads assoc module promise)
                 promise))
           (.then (fn [spec]
                    ;; Per-call component dependencies may depend on route args,
                    ;; even when the module itself is already initialized.
                    (-> (js/Promise.all
                         #js [(prepare-view! spec view args)
                              (when (and route (:route-depends spec)
                                         (not= (:path route) (:path @context/*snapshot)))
                                (prepare-data! ((:route-depends spec) route)))])
                        (.then (fn [_] (if post-fn (apply post-fn spec args) spec))))))))
    (js/Promise.reject (ex-info "Unknown module" {:module module}))))

(defn code-spec
  "Already-loaded code is renderable even while its managed data is pending."
  [module]
  (when-let [loadable (get modules module)]
    (when (ready? module) @loadable)))

(declare load-code!)

(defn load-code!
  "Navigation waits for JavaScript and CSS. Initialization/data run independently;
   managed component bindings own their loading and error views."
  [options]
  (let [module (:module options)]
    (-> (load! options)
        (.then (fn [_] (status/recover! [:module module])))
        (.catch (fn [error]
                  (status/fail! [:module module] "Section initialization failed"
                                "This section could not finish loading. Retry to load it again."
                                #(load-code! options)))))
    (acquire-code! module)))

(rf/reg-event-db :loader/code-ready
  (fn [db [_ module]] (-> db (assoc-in [:loader :code-ready module] true)
                         (update-in [:loader :errors] (fnil dissoc {}) module))))
(rf/reg-event-db :loader/code-failed
  (fn [db [_ module error]]
    (-> db
        (assoc-in [:loader :errors module] error)
        (update-in [:loader :requested] (fnil dissoc {}) module))))
(rf/reg-sub :loader/code-error
  (fn [db query]
    (let [[_ module] (or (:re-frame/query-v query) query)]
      (get-in db [:loader :errors module]))))
(rf/reg-sub :loader/code-ready
  (fn [db query]
    (let [[_ module] (or (:re-frame/query-v query) query)]
      (get-in db [:loader :code-ready module]))))
(rf/reg-sub :loader/code-modules
  (fn [db _] (keys (get-in db [:loader :code-ready]))))
(defn- request-module [db module effect]
  (when-not (get-in db [:loader :requested module])
    {:db (-> db
             (assoc-in [:loader :requested module] true)
             (update-in [:loader :errors] (fnil dissoc {}) module))
     :fx effect}))

(rf/reg-event-fx :loader/acquire
  (fn [{:keys [db]} [_ module]]
    (request-module db module [[:loader/acquire module]])))
(rf/reg-event-fx :loader/activate
  {:args [:cat activation/options-schema]}
  (fn [{:keys [db]} [_ {:keys [module args] :as options}]]
    (request-module db module
                    [[:dispatch [:scope/init module args]]
                     [:loader/acquire options]])))
(rf/reg-fx :loader/acquire
  (fn [value]
    ;; Initialization failures are reported by load-code!'s managed status path.
    (let [options (if (keyword? value) {:module value} value)
          module (:module options)]
      (-> (load-code! options)
          (.catch (fn [error] (rf/dispatch [:loader/code-failed module error])))))))
(rf/reg-sub-raw :loader/module
  (fn [_ query]
    (let [[_ module] (or (:re-frame/query-v query) query)]
      ;; :scope/inited? records activation, which may precede code arrival.
      ;; One cached subscription acquires code/init; its reaction only reads db.
      (rf/dispatch [:loader/acquire module])
      (ratom/make-reaction #(deref (rf/subscribe [:loader/code-ready module]))))))

(m/defc <assets>
  "Inject external assets"
  [{:keys [css js]}]
  [:<>
   (m/for [src css]
          [:link {:rel  "stylesheet"
                  :type "text/css"
                  :href src}])
   (m/for [src js]
          [:script {:type "text/javascript"
                    :src  src}])])

(m/defc <loaded-assets>
  "The page owns external module assets; direct vectors need no asset wrapper."
  [page-assets]
  (let [loaded @(rf/subscribe [:loader/code-modules])
        assets (cons page-assets (map #(get (code-spec %) :assets) loaded))]
    [<assets> {:css (vec (distinct (mapcat :css assets)))
               :js (vec (distinct (mapcat :js assets)))}]))

(declare <>)
(defn- component-form [view args options]
  (let [form (into [(component/resolve-view view)] args)]
    (if-let [skeleton (or (:skeleton options) (:skeleton (first args)))]
      [loading/<rendered> {:form form :class (when (map? skeleton) (:class skeleton))}]
      form)))

(defn- fallback-form [{:keys [<before> <loading>]} args pending?]
  (cond
    <before> [:div.before-loading-container
              (if (vector? <before>) <before> (into [<before>] args))]
    <loading> (if (vector? <loading>) <loading> (into [<loading>] args))
    pending? [:div.loading-container [loading/<spinner>]]
    :else [loading/<placeholder> {:loading-tag :section :loading-prefab :text}]))

(m/defc <trigger>
  "An optional earlier section can activate a later module through the same loader."
  [options :- activation/options-schema]
  (let [*element (rf/use-ref nil)
        active? @(rf/subscribe [:scope/inited? (:module options)])]
    (rf/use-effect
      (fn []
        (if (and (not context/*server?*) (not active?))
          (or (activation/setup! (.-current *element) options) js/undefined)
          js/undefined))
      #js [options active?])
    (when-not active?
      [:span.module-load-trigger {:ref *element :aria-hidden true}])))

(m/defc ^:private <module-content>
  [{:keys [module view <missing>] :as options} args complete! hydrating?]
  (let [_ (when-not context/*server?* @(rf/subscribe [:loader/code-ready module]))
        failure (when-not context/*server?* @(rf/subscribe [:loader/code-error module]))
        loaded (if context/*server?* (get context/*modules* module) (code-spec module))
        resolved (get-in loaded [:view (or view :view)])]
    (rf/use-layout-effect
      (fn [] (when (or resolved failure loaded) (complete!)) js/undefined)
      #js [(boolean (or resolved failure loaded))])
    [:r> (rf/context-provider restore/hydrating-context) #js {:value hydrating?}
     (cond
       failure [error/<failure> "module" (name module)
                {:title "This section could not be loaded" :error failure}
                #(rf/dispatch [:loader/activate (assoc options :args args)])]
       resolved (component-form resolved args options)
       loaded (if <missing>
                (if (vector? <missing>) <missing> (into [<missing>] args))
                [<default-missing> module view])
       :else (fallback-form options args true))]))

(defn- make-boundary [module]
  (let [*release (atom nil)
        gate (js/Promise. (fn [resolve _] (reset! *release resolve)))
        content #js {:default (rf/reactify-component
                               (fn [{:keys [options args complete! hydrating?]}]
                                 [<module-content> options args complete! hydrating?]))}]
    {:release! #(@*release nil)
     :view (rf/lazy
             (fn []
               (-> gate
                   (.then (fn [_] (acquire-code! module)))
                   (.then (fn [_] content))
                   (.catch (fn [error]
                             ;; A failed module owns its Retry view; never reject
                             ;; through the entire page's error boundary.
                             (rf/dispatch [:loader/code-failed module error])
                             content)))))}))

(m/defc ^:private <module-boundary>
  [{:keys [module view defer? <before>] :as options} args]
  (let [active? (boolean @(rf/subscribe [:scope/inited? module]))
        hidden? (and (or defer? <before>)
                     (or (and (not @context/*interactive?) (not (restore/local-document?)))
                         (not active?)))
        [ssr?] (rf/use-state
                 #(boolean (and (not hidden?)
                                (or (and context/*server?*
                                         (get-in context/*modules* [module :view (or view :view)]))
                                    (and (restore/initial-hydration?)
                                         (restore/rendered-view? module (or view :view)))))))
        [initial?] (rf/use-state #(and (not context/*server?*) ssr? (restore/initial-hydration?)))
        [committed? set-committed!] (rf/use-state false)
        *trigger (rf/use-ref nil)
        release! (hydration/use-deferred! initial?)
        complete! (rf/use-callback (fn [] (release!) (set-committed! true)) #js [release!])
        boundary (rf/use-memo #(make-boundary module) #js [module])
        ready? (or context/*server?* (boolean @(rf/subscribe [:loader/code-ready module])))
        failure (when-not context/*server?* @(rf/subscribe [:loader/code-error module]))
        modes (activation/triggers options)
        requested? (and (not hidden?) active?)]
    (restore/use-readiness! (or (not requested?) ready? (some? failure)))
    (rf/use-layout-effect
      (fn []
        (if (and (not context/*server?*) ssr?)
          (listen-code! module (:release! boundary))
          js/undefined))
      #js [module ssr? boundary])
    (rf/use-effect
      (fn []
        (if (and (not context/*server?*) (not hidden?) (or active? (modes :immediate)))
          (do
            (rf/dispatch [:loader/activate (assoc options :args args)])
            ((:release! boundary))
            js/undefined)
          js/undefined))
      #js [module active? hidden? boundary])
    (rf/use-layout-effect
      (fn []
        (if (and (not context/*server?*) (not active?) (not committed?) (.-current *trigger))
          (let [marker (.-current *trigger)
                target (if ssr? (.-nextElementSibling marker) marker)]
            (or (activation/setup! target (assoc options :args args)) js/undefined))
          js/undefined))
      #js [module options args active? committed? ssr?])
    (cond
      hidden? (when <before>
                [:div.before-loading-container
                 {:ref *trigger
                  :on-click #(rf/dispatch [:loader/activate (assoc options :args args)])}
                 (if (vector? <before>) <before> (into [<before>] args))])
      ssr? [:<>
            (when-not committed? ^{:key :module-marker}
              [:template {:ref *trigger :data-module-boundary (name module)}])
            ^{:key :module-content}
            [rf/suspense {:fallback (rf/as-element (fallback-form options args true))}
             (if context/*server?*
               [<module-content> options args (fn []) true]
               (rf/create-element (:view boundary)
                 #js {:options options :args args :complete! complete!
                      :hydrating? (and initial? (not committed?))}))]]
      (and requested? (or ready? failure)) [<module-content> options args complete! false]
      :else [:section.module-load-placeholder {:ref *trigger}
             (fallback-form options args requested?)])))

(defn component-vector
  "Module boundaries own activation and preserve completed SSR until hydration.
   Direct component references keep their ordinary native root."
  [reference args]
  (let [reference (if (qualified-keyword? reference)
                    {:module (keyword (namespace reference)) :view (keyword (name reference))}
                    reference)]
    (if-not (or (map? reference) (vector? reference))
      (component-form reference args nil)
      (let [options (if (vector? reference)
                      {:module (first reference) :view (second reference)} reference)]
        [<module-boundary> options (vec args)]))))
