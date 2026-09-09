(ns tolgraven.loader
  (:require
   [re-frame.core :as rf]
   [reagent.core :as r]
   [shadow.lazy :as lazy :rename {loadable l}])
  (:require-macros
   [tolgraven.macros :as m]))

(def modules (merge (m/make-modules "tolgraven" [:blog
                                                 :search
                                                 :user
                                                 :chat
                                                 :github
                                                 :cv
                                                 :docs
                                                 :gpt
                                                 :strava
                                                 :instagram])
                    {:test (l tolgraven.experiments/spec)}))

(defn <default-missing>
  [& args]
  [:div (pr-str args)])

(defonce *loads (atom {}))

(defn ready? [module]
  (when-let [loadable (get modules module)]
    (lazy/ready? loadable)))

(defn load!
  "Return a promise for an initialized module. Concurrent callers share one load.
   Hooks run per caller; module initialization runs once, including bundled modules."
  [{:keys [module init-evt pre-fn post-fn args]}]
  (if-let [loadable (get modules module)]
    (do
      (when init-evt (rf/dispatch init-evt))
      (when pre-fn (apply pre-fn args))
      (let [loaded (or (get @*loads module)
                       (let [promise (-> (if (lazy/ready? loadable)
                                           (js/Promise.resolve @loadable)
                                           (js/Promise.resolve (lazy/load loadable)))
                                         (.then (fn [spec]
                                                  (rf/dispatch [:scope/init module])
                                                  (when-let [init (:init spec)]
                                                    (apply init args))
                                                  spec))
                                         (.catch (fn [error]
                                                   (swap! *loads dissoc module)
                                                   (throw error))))]
                         (swap! *loads assoc module promise)
                         promise))]
        (.then loaded (fn [spec]
                        (if post-fn (apply post-fn spec args) spec)))))
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
      (let [{:keys [module view defer? <before> <loading> <missing> post-fn assets]}
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
          (-> (load! {:module module})
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
             {:on-click #(rf/dispatch [:scope/init module])}
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
               (into [component] args)
               (if <missing>
                 (if (vector? <missing>) <missing> (into [<missing>] args))
                 [<default-missing> module view]))])

          :else
          (if <loading>
            (if (vector? <loading>) <loading> (into [<loading>] args))
            [:div.loading-container [:div.loading-spinner]]))))))
