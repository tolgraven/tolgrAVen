(ns tolgraven.loader
  (:require
   [re-frame.core :as rf]
   [tolgraven.content.client :as content]
   [tolgraven.content.contract :as content-contract]
   [tolgraven.component :as component]
   [tolgraven.component.data :as data]
   [reagent.core :as r]
   [shadow.lazy :as lazy])
  (:require-macros
   [tolgraven.macros :as m]))

(def modules (merge (m/make-modules "tolgraven" [:blog
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
                    {:test (lazy/loadable tolgraven.experiments/spec)}))

(defn <default-missing>
  [& args]
  [:div (pr-str args)])

(defonce *loads (atom {}))

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
  [{:keys [module view init-evt pre-fn post-fn args]}]
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
                        (-> (prepare-view! spec view args)
                            (.then (fn [_] (if post-fn (apply post-fn spec args) spec))))))))
    (js/Promise.reject (ex-info "Unknown module" {:module module}))))

(defn <assets>
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

(defn <>
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
            deferred? (and (or defer? <before>) (not requested?))]
        (when (and (not deferred?) (not= module @*requested))
          (reset! *requested module)
          (reset! *loaded nil)
          (reset! *error nil)
          ;; The component handles post-fn below so its return value cannot replace
          ;; the loaded module spec. Forward the other hooks and initialization args.
          (-> (load! (-> load-spec
                        (dissoc :post-fn)
                        (assoc :args args)))
              (.then (fn [loaded]
                       (when (= module @*requested)
                         (reset! *loaded loaded)
                         (when post-fn (apply post-fn loaded args)))))
              (.catch (fn [error]
                        (when (= module @*requested)
                          (reset! *error error))))))
        (cond
          deferred?
          (when <before>
            [:div.before-loading-container
             {:on-click #(rf/dispatch [:scope/init module args])}
             (if (vector? <before>) <before> (into [<before>] args))])

          @*error
          [:div.module-error
           [:p "This section could not be loaded."]
           [:button {:on-click #(do (reset! *requested nil)
                                    (reset! *error nil))}
            "Retry"]]

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
