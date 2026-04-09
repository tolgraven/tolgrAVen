(ns tolgraven.loader
  (:require
   [re-frame.core :as rf]
   [react :as react]
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

(defn ready?
  [module]
  (lazy/ready? (get modules module)))

(defn load!
  "Loads a component asynchronously. Any kind of load must always use this fn,
  because it handles init events, pre- and post-load hooks, etc."
  [{:keys [module init-evt pre-fn post-fn args] :as spec}]
  (let [*spec (get modules module)]
    (if-not *spec
      (throw (ex-info "Unknown module" {:k module}))
      (if (lazy/ready? *spec)
        @*spec
        (do
          (when init-evt (rf/dispatch init-evt))
          (when pre-fn (apply pre-fn args))
          #_(when-not @(rf/subscribe [:scope/init? module-]) ; doesn't work here obvs, figure out how to get this info here from app-db?
            (rf/dispatch [:scope/init module- args]) )
          (-> (lazy/load *spec)
              (.then (fn [sym]
                       (rf/dispatch [:scope/init module args])
                       (when-let [init (:init sym)]             ; here because sub needs to be within a view component
                         (apply init args))
                       (when post-fn
                         (apply post-fn sym args))))))))))

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
  "Loads a component as part of react-built DOM"
  [{:keys [<before> defer?]}
   & _]
  (let [*defer? (r/atom (or defer? <before>))]
    (fn [{:keys [module view post-fn <loading> <missing> <before> load-sub] :as spec}
         & args]
      (let [module-      (or module
                             (when (vector? spec) (first spec)))                     ; if vector
            view-        (or view
                             (when (vector? spec) (second spec))
                             :view)
            *module-spec (get modules module-)]
        (letfn [(<missing>' [& args]
                  (if <missing>
                    (if (vector? <missing>)
                      <missing>
                      (into [<missing>] args))
                    (into [<default-missing>] args)))
                (<before>' []
                  (when <before>
                    [:div.before-loading-container
                     {:on-click #(rf/dispatch [:scope/init module- args])}
                     #_{:on-click #(reset! *do-load? true)}
                     [<before> args]]))
                (<loading>' []
                  (or (if (fn? <loading>) (into [<loading>] args) <loading>)
                      [:div.loading-container>loading-spinner]))
                (-><inner> [comp-spec]
                  (let [<comp> (some-> comp-spec :view view- deref)]
                    (fn []
                      #_(when-not @(rf/subscribe [:scope/init? module-])
                          (rf/dispatch [:scope/init module- args]))
                      (if (fn? <comp>)
                        (into [<comp>] args)
                        (do (when-not (:no-warn? spec)
                              (if <comp>
                                (js/console.error
                                 "Failed to extract lazy comp:"
                                 #js {:module module-, :view view-, :<comp> <comp>})
                                (js/console.error "Failed to deref lazy:" spec)))
                            (into [<missing>'] args))))))
                (load-then-show []
                  (load! (assoc spec
                           :post-fn (fn [sym & args]
                                      (when post-fn
                                        (apply post-fn sym args))
                                      #js {:default (r/reactify-component (-><inner> sym))})
                           :args args)))]
          (if (and @*defer?
                   (not @(rf/subscribe [:scope/inited? module-])))
            (when <before>'
              [<before>'])
            (if (lazy/ready? *module-spec)
              [(-><inner> @*module-spec)]
              [:> react/Suspense
               {:fallback (r/as-element [<loading>'])}
               (cond->> (into [:> (react/lazy load-then-show)] args)
                 (:assets spec) (into [:<> [<assets> (:assets spec)]]))])))))))
