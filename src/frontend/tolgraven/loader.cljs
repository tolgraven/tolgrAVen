(ns tolgraven.loader
  (:require
    [tolgraven.component.registry]
    [tolgraven.validation.runtime :as validation]
    [tolgraven.loader.code :as module-code]
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

(defn acquire-code!
  "One Shadow acquisition shared by navigation, initialization and subscribers.
   The completion event makes Shadow readiness observable through re-frame."
  [module]
  (or (get @*code-loads module)
      (let [promise (-> (try
                          (if-let [loadable (get modules module)]
                            (js/Promise.resolve (if (lazy/ready? loadable) @loadable (lazy/load loadable)))
                            (js/Promise.reject (ex-info "Unknown module" {:module module})))
                          (catch :default error (js/Promise.reject error)))
                        (.then (fn [spec]
                                 (validation/module! spec)
                                 (rf/dispatch [:loader/code-ready module])
                                 spec))
                        (.catch (fn [error]
                                  (swap! *code-loads dissoc module)
                                  (throw error))))]
        (swap! *code-loads assoc module promise)
        promise)))

(defn ready? [module]
  (when-let [loadable (get modules module)]
    (lazy/ready? loadable)))

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
    (when (lazy/ready? loadable) @loadable)))

(declare load-code!)

(defn load-code!
  "Navigation waits only for JavaScript. Initialization/data run independently;
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
  (fn [db [_ module error]] (assoc-in db [:loader :errors module] error)))
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
(rf/reg-event-fx :loader/acquire
  (fn [{:keys [db]} [_ module]]
    {:db (update-in db [:loader :errors] (fnil dissoc {}) module) :loader/acquire module}))
(rf/reg-fx :loader/acquire
  (fn [module]
    ;; Initialization failures are reported by load-code!'s managed status path.
    (-> (load-code! {:module module})
        (.catch (fn [error] (rf/dispatch [:loader/code-failed module error]))))))
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

(defn component-vector
  "Pure vector selection, reactive to shared module-code acquisition. Data stays
   with the component's ordinary declared bindings, never delays this selection."
  [reference args]
  (let [reference (if (qualified-keyword? reference)
                    {:module (keyword (namespace reference)) :view (keyword (name reference))}
                    reference)]
    (if-not (or (map? reference) (vector? reference))
      (component-form reference args nil)
      (let [{:keys [module view defer? <before>] :as options}
            (if (vector? reference) {:module (first reference) :view (second reference)} reference)
            deferred? (and (or defer? <before>)
                           (or (and (not @context/*interactive?) (not (restore/local-document?)))
                               (and (or (not context/*server?*) (restore/local-document?))
                                    (not @(rf/subscribe [:scope/inited? module])))))
            _ (when (and (not context/*server?*) (not deferred?))
                @(rf/subscribe [:loader/module module]))
            spec (if context/*server?* (get context/*modules* module) (code-spec module))
            resolved (when-not deferred? (get-in spec [:view (or view :view)]))]
        (if resolved
          (component-form resolved args options)
          (into [<> options] args))))))

(m/defc ^:private <pending-module>
  "Temporary fallback only. Acquisition belongs to the shared source adapter;
   renders contain no Promise chains or imperative initialization."
  [spec & args]
  (let [{:keys [module view defer? <before> <loading> <missing>] :as options}
        (if (vector? spec) {:module (first spec) :view (second spec)} spec)
        deferred? (and (or defer? <before>)
                       (or (and (not @context/*interactive?) (not (restore/local-document?)))
                           (not @(rf/subscribe [:scope/inited? module]))))
        _ (when-not deferred? @(rf/subscribe [:loader/module module]))
        failure @(rf/subscribe [:loader/code-error module])
        loaded (when-not deferred? (code-spec module))
        resolved (get-in loaded [:view (or view :view)])]
    (cond
      deferred? (when <before>
                  [:div.before-loading-container
                   {:on-click #(rf/dispatch [:scope/init module args])}
                   (if (vector? <before>) <before> (into [<before>] args))])
      resolved (component-form resolved args options)
      failure [error/<failure> "module" (name module)
               {:title "This section could not be loaded" :error failure}
               #(rf/dispatch [:loader/acquire module])]
      loaded (if <missing>
               (if (vector? <missing>) <missing> (into [<missing>] args))
               [<default-missing> module view])
      <loading> (if (vector? <loading>) <loading> (into [<loading>] args))
      :else [:div.loading-container [:div.loading-spinner]])))

(m/defc ^:private <>
  "Internal pending/deferred fallback. Application views use m/<>."
  [& initial]
  (if context/*server?*
    (fn [spec & args]
      (let [{:keys [module view defer? <before>]} (if (vector? spec)
                                                    {:module (first spec) :view (second spec)} spec)]
        (cond
          (or defer? <before>) (when <before> [:div.before-loading-container
                                             (if (vector? <before>) <before> (into [<before>] args))])
          :else (when-let [view (get-in context/*modules* [module :view (or view :view)])]
                  (into [(component/resolve-view view)] args)))))
    (into [<pending-module>] initial)))
