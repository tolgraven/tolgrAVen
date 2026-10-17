(ns website.loader.activation
  "Owned module proximity/intent triggers. React keeps ownership of the observed UI."
  (:require [website.component.visibility :as visibility]
            [website.react :as r]
            [website.listener :as listener]))

(def trigger [:enum :view :hover :click :event :immediate])
(def options-schema
  [:map
   [:module :keyword]
   [:?load-on [:or trigger [:set trigger] [:vector trigger]]]
   [:?lead-pages [:and number? [:>= 0] [:<= 10]]]
   [:?root-margin :string]
   [:?args [:sequential :any]]
   [:?event [:cat :keyword [:* :any]]]])

(defn triggers [options]
  (let [value (or (:load-on options)
                  (when (:<before> options) :click)
                  (when (:defer? options) :event)
                  :view)]
    (if (keyword? value) #{value} (set value))))

(defn- root-margin [options]
  ;; IntersectionObserver percentages resolve against root width. Convert the
  ;; requested number of viewport heights without introducing CSS pixel sizes.
  (or (:root-margin options)
      (str (* 100 (or (:lead-pages options) 2)
              (/ (.-innerHeight js/window) (max 1 (.-innerWidth js/window))))
           "% 0%")))

(defn setup!
  ([element options] (setup! element options [element]))
  ([element options geometry-targets]
   (when (and element (exists? js/window))
     (let [modes (triggers options)
           *active? (atom true)
           *fired? (atom false)
           *visibility (atom [])
           fire! (fn [& _]
                   (when (and @*active? (compare-and-set! *fired? false true))
                     (r/dispatch (or (:event options)
                                      [:loader/activate (dissoc options :event)]))))
           observe! (fn []
                      (doseq [state @*visibility] ((:stop! state)))
                      (reset! *visibility [])
                      (when (and @*active? (not @*fired?) (modes :view))
                        (reset! *visibility
                          (mapv (fn [target]
                                  (let [state (visibility/setup {:callback fire!
                                                                 :threshold 0
                                                                 :root-margin (root-margin options)})]
                                    ((:mount! state) target)
                                    state))
                                geometry-targets))))
           ;; Document capture runs before React's root capture listener queues a
           ;; click on unhydrated Suspense content. Observe intent only inside this
           ;; owned root; never cancel the event or mutate its markup.
           intent! (fn [event]
                     (when (.contains element (.-target event)) (fire!)))
           events (cond-> []
                    (some modes [:view :hover :click]) (conj "focusin")
                    (or (modes :click) (modes :view)) (conj "click")
                    (or (modes :hover) (modes :view)) (conj "pointerenter"))
           bindings (cond-> (mapv (fn [event]
                                   {:target   "document"
                                    :event    event
                                    :handler  intent!
                                    :capture? true}) events)
                      (modes :view) (conj {:target "window", :event "resize", :handler observe!}))
           stop-listeners! (listener/listen! :loader-activation bindings)]
       (when (modes :view)
         (observe!))
       (when (modes :immediate) (fire!))
       (fn []
         (reset! *active? false)
         (doseq [state @*visibility] ((:stop! state)))
         (stop-listeners!))))))
