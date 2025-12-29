(ns tolgraven.loader
  (:require
   [re-frame.core :as rf]
   [react :as react]
   [reagent.core :as r]
   [shadow.lazy :as lazy :rename {loadable l}])
   (:require-macros
     [tolgraven.macros :as m]))

#_(def modules
  {:blog (l tolgraven.blog.module/spec)
   :search (l tolgraven.search.module/spec)
   :user (l tolgraven.user.module/spec)
   :chat (l tolgraven.chat.module/spec)
   :github (l tolgraven.github.module/spec)
   :docs (l tolgraven.docs.module/spec)
   :gpt (l tolgraven.gpt.module/spec)
   :strava (l tolgraven.strava.module/spec)
   :instagram (l tolgraven.instagram.module/spec)
   :test (l tolgraven.experiments/spec)})

#_(def module-ks (keys modules))

(def modules (merge (m/make-modules "tolgraven" [:blog
                                                 :search
                                                 :user
                                                 :chat
                                                 :github
                                                 :docs
                                                 :gpt
                                                 :strava
                                                 :instagram])
                    {:test (l tolgraven.experiments/spec)}))

(defn <default-missing>
  [& args]
  [:div (pr-str args)])

(defn load!
  "Loads a component asynchronously."
  [{:keys [module init-evt pre-fn post-fn args] :as spec}]
  (let [*spec (get modules module)]
    (if-not *spec
      (throw (ex-info "Unknown module" {:k module}))
      (if (lazy/ready? *spec)
        @*spec
        (do
          (when init-evt (rf/dispatch init-evt))
          (when pre-fn (apply pre-fn args))
          (-> (lazy/load *spec)
              (.then (fn [sym]
                         (when post-fn
                           (apply post-fn sym args))))))))))

(defn <lazy>
  "Loads a component as part of react-built DOM"
  [{:keys [module view post-fn <loading> <missing>] :as spec}
   & args]
  (let [*module-spec (get modules module)]
    (letfn [(<missing>' [& args]
              (if <missing>
                (if (vector? <missing>)
                  <missing>
                  (into [<missing>] args))
                (into [<default-missing>] args)))
            (<loading>' []
              (or (if (fn? <loading>) (into [<loading>] args) <loading>)
                  (into [:div] args)))
            (-><inner> [comp-spec]
              (let [<comp> (some-> comp-spec :view view deref)]
                (fn []
                  (js/console.log "<inner>:" comp-spec <comp>)
                  (if (fn? <comp>)
                    (into [<comp>] args)
                    (do (when-not (:no-warn? spec)
                          (if <comp>
                            (js/console.error
                              "Failed to extract lazy comp:"
                              #js {:module module, :view view, :<comp> <comp>})
                            (js/console.error "Failed to deref lazy:" spec)))
                        (into [<missing>'] args))))))
            (load-then-show []
              (load! (assoc spec
                       :post-fn (fn [sym & args]
                                  (when post-fn
                                    (apply post-fn sym args))
                                  #js {:default (r/reactify-component (-><inner> sym))})
                       :args args)))]
      (if (lazy/ready? *module-spec)
        [(-><inner> @*module-spec)]
        [:> react/Suspense
         {:fallback (r/as-element [<loading>'])}
         (into [:> (react/lazy load-then-show)] args)]))))
