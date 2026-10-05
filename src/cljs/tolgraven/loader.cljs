(ns tolgraven.loader
  (:require
    [tolgraven.component.registry]
    [tolgraven.react :as rf]
    [tolgraven.render-context :as context]
    [tolgraven.content.client :as content]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.component :as component]
    [tolgraven.component.data :as data]
    [tolgraven.components.error :as error]
    [tolgraven.service-status :as status]
    [reagent.core :as r]
    [reagent.ratom :as ratom]
    [shadow.lazy :as lazy])
  (:require-macros
    [tolgraven.macros :as m]))

(def modules (m/browser-only (merge (m/make-modules "tolgraven" [:blog
                                                 :link-preview
                                                 :search
                                                 :user
                                                 :chat
                                                 :github
                                                 :cv
                                                 :docs
                                                 :gpt
                                                 :strava
                                                 :instagram])
                    {:test (lazy/loadable tolgraven.experiments/spec)})))

(m/defc <default-missing>
  [& args]
  [:div (pr-str args)])

(defonce *loads (atom {}))

(defn ready? [module]
  (when-let [loadable (get modules module)]
    (lazy/ready? loadable)))

(defn ready-spec
  "Return bundled/cached code only when its module and view data are available.
   Promise-based initialization still runs, but an already-ready view need not
   disappear behind a spinner while that promise's callbacks are scheduled."
  [module view args]
  (when-let [loadable (get modules module)]
    (when (lazy/ready? loadable)
      (let [spec @loadable
            definition (component/component-spec (get-in spec [:view (or view :view)]))
            resources (concat (:depends spec)
                              (get content-contract/module-dependencies module)
                              (when (seq (:content spec)) [{:source :strapi :keys (:content spec)}])
                              (when definition (component/dependencies definition args)))]
        (when (= :ready (data/state resources)) spec)))))

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
      (let [loaded (or (get @*loads module)
                       (let [known-data (prepare-data! (get content-contract/module-dependencies module []))
                             code (try
                                    (if (lazy/ready? loadable)
                                      (js/Promise.resolve @loadable)
                                      (js/Promise.resolve (lazy/load loadable)))
                                    (catch :default error (js/Promise.reject error)))
                             promise (-> (js/Promise.all #js [code known-data])
                                         (.then (fn [loaded]
                                                  (let [spec (aget loaded 0)]
                                                   (-> (js/Promise.all
                                                        #js [(content/ensure! (:content spec))
                                                             (prepare-data! (:depends spec))
                                                             (prepare-view! spec view args)])
                                                      (.then (fn []
                                                               (rf/dispatch [:scope/init module args])
                                                               (when-let [init (:init spec)]
                                                                 (apply init args))
                                                               spec))))))
                                         (.catch (fn [error]
                                                   (swap! *loads dissoc module)
                                                   (throw error))))]
                         (swap! *loads assoc module promise)
                         promise))]
        (.then loaded (fn [spec]
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
    (-> (if-let [loadable (get modules module)]
          (if (lazy/ready? loadable)
            (js/Promise.resolve @loadable)
            (js/Promise.resolve (lazy/load loadable)))
          (js/Promise.reject (ex-info "Unknown module" {:module module})))
        (.then (fn [spec]
                 (rf/dispatch [:loader/code-ready module])
                 spec)))))

(rf/reg-event-db :loader/code-ready
  (fn [db [_ module]] (-> db (assoc-in [:loader :code-ready module] true)
                         (update-in [:loader :errors] dissoc module))))
(rf/reg-event-db :loader/code-failed
  (fn [db [_ module error]] (assoc-in db [:loader :errors module] error)))
(rf/reg-sub :loader/code-error
  (fn [db [_ module]] (get-in db [:loader :errors module])))
(rf/reg-sub :loader/code-ready
  (fn [db [_ module]] (get-in db [:loader :code-ready module])))
(rf/reg-sub :loader/code-modules
  (fn [db _] (keys (get-in db [:loader :code-ready]))))
(rf/reg-event-fx :loader/acquire
  (fn [{:keys [db]} [_ module]]
    {:db (update-in db [:loader :errors] dissoc module) :loader/acquire module}))
(rf/reg-fx :loader/acquire
  (fn [module]
    ;; Initialization failures are reported by load-code!'s managed status path.
    (-> (load-code! {:module module})
        (.catch (fn [error] (rf/dispatch [:loader/code-failed module error]))))))
(rf/reg-sub-raw :loader/module
  (fn [_ [_ module]]
    ;; Acquisition happens once per shared subscription, outside its pure read.
    ;; Shadow code and initialized module caches outlive individual consumers.
    (rf/dispatch [:loader/acquire module])
    (ratom/make-reaction #(deref (rf/subscribe [:loader/code-ready module])))))

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
  []
  (let [loaded @(rf/subscribe [:loader/code-modules])
        assets (map #(get (code-spec %) :assets) loaded)]
    [<assets> {:css (vec (distinct (mapcat :css assets)))
               :js (vec (distinct (mapcat :js assets)))}]))

(declare <>)
(defn component-vector
  "Pure vector selection, reactive to shared module-code acquisition. Data stays
   with the component's ordinary declared bindings, never delays this selection."
  [reference args]
  (if-not (or (map? reference) (vector? reference))
    (into [(component/resolve-view reference)] args)
    (let [{:keys [module view defer? <before>] :as options}
          (if (vector? reference) {:module (first reference) :view (second reference)} reference)
          deferred? (and (or defer? <before>)
                         (or (not @context/*interactive?)
                             (and (not context/*server?*)
                                  (not @(rf/subscribe [:scope/inited? module])))))
          _ (when (and (not context/*server?*) (not deferred?))
              @(rf/subscribe [:loader/module module]))
          spec (if context/*server?* (get context/*modules* module) (code-spec module))
          resolved (when-not deferred? (get-in spec [:view (or view :view)]))]
      (if resolved
        (into [(component/resolve-view resolved)] args)
        (into [<> options] args)))))

(defn make-browser-render
  "Render a module component after loading and initialization, optionally on demand."
  [& _]
  (let [*loaded (r/atom nil)
        *error (r/atom nil)
        *requested (atom nil)]
    (fn [spec & args]
      (let [{:keys [module view defer? <before> <loading> <missing> post-fn assets]
             :as load-spec}
            (if (vector? spec)
              {:module (first spec) :view (second spec)}
              spec)
            view (or view :view)
            requested? @(rf/subscribe [:scope/inited? module])
            deferred? (and (or defer? <before>)
                           (or (not requested?) (not @context/*interactive?)))]
        (when (and (not deferred?) (not= module @*requested))
          (reset! *requested module)
          (reset! *loaded (ready-spec module view args))
          (reset! *error nil)
          ;; The component handles post-fn below so its return value cannot replace
          ;; the loaded module spec. Forward the other hooks and initialization args.
          (-> (load! (-> load-spec
                        (dissoc :post-fn)
                        (assoc :args args)))
              (.then (fn [loaded]
                       (when (= module @*requested)
                         (reset! *loaded loaded)
                         (status/recover! [:module module])
                         (when post-fn (apply post-fn loaded args)))))
              (.catch (fn [error]
                        (when (= module @*requested)
                          (reset! *error error)
                          (status/fail! [:module module] "Section unavailable"
                                        "This section could not be loaded. Retry to load it again."
                                        #(do (reset! *requested nil) (reset! *error nil))))))))
        (cond
          deferred?
          (when <before>
            [:div.before-loading-container
             {:on-click #(rf/dispatch [:scope/init module args])}
             (if (vector? <before>) <before> (into [<before>] args))])

          (and @*error (nil? @*loaded))
          [error/<failure> "module" (name module)
           {:title "This section could not be loaded"
            :message "Check your connection and try loading this section again."
            :error @*error}
           #(do (reset! *requested nil) (reset! *error nil))]

          @*loaded
          (let [component (get-in @*loaded [:view view])]
            [:<>
             [<assets> (merge-with into (:assets @*loaded) assets)]
             (if component
               (into [(component/resolve-view component)] args)
               (if <missing>
                 (if (vector? <missing>) <missing> (into [<missing>] args))
                 [<default-missing> module view]))])

          :else
          (if <loading>
            (if (vector? <loading>) <loading> (into [<loading>] args))
            [:div.loading-container [:div.loading-spinner]]))))))

(m/defc ^:private <browser-module> [& _]
  (make-browser-render))

(m/defc ^:private <pending-module>
  "Temporary fallback only. Acquisition belongs to the shared source adapter;
   renders contain no Promise chains or imperative initialization."
  [spec & args]
  (let [{:keys [module view defer? <before> <loading> <missing>] :as options}
        (if (vector? spec) {:module (first spec) :view (second spec)} spec)
        deferred? (and (or defer? <before>)
                       (or (not @context/*interactive?)
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
      resolved (into [(component/resolve-view resolved)] args)
      failure [error/<failure> "module" (name module)
               {:title "This section could not be loaded" :error failure}
               #(rf/dispatch [:loader/acquire module])]
      loaded (if <missing>
               (if (vector? <missing>) <missing> (into [<missing>] args))
               [<default-missing> module view])
      <loading> (if (vector? <loading>) <loading> (into [<loading>] args))
      :else [:div.loading-container [:div.loading-spinner]])))

(m/defc <>
  "Use the same module view in Node, without starting browser initialization."
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
