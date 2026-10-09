(ns tolgraven.loader.activation
  "Owned module proximity/intent triggers. React keeps ownership of the observed UI."
  (:require [tolgraven.component.visibility :as visibility]
            [tolgraven.react :as rf]))

(def trigger [:enum :view :hover :click :event :immediate])
(def options-schema
  [:map
   [:module :keyword]
   [:load-on {:optional true} [:or trigger [:set trigger] [:vector trigger]]]
   [:lead-pages {:optional true} [:and number? [:>= 0] [:<= 10]]]
   [:root-margin {:optional true} :string]
   [:args {:optional true} [:sequential :any]]
   [:event {:optional true} [:cat :keyword [:* :any]]]])

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

(defn setup! [element options]
  (when (and element (exists? js/window))
    (let [modes (triggers options)
          *active? (atom true)
          *fired? (atom false)
          *visibility (atom nil)
          fire! (fn [& _]
                  (when (and @*active? (compare-and-set! *fired? false true))
                    (rf/dispatch (or (:event options)
                                     [:loader/activate (dissoc options :event)]))))
          observe! (fn []
                     (when-let [state @*visibility] ((:stop! state)))
                     (when (and @*active? (not @*fired?) (modes :view))
                       (let [state (visibility/setup {:callback fire!
                                                      :threshold 0
                                                      :root-margin (root-margin options)})]
                         (reset! *visibility state)
                         ((:mount! state) element))))
          ;; Document capture runs before React's root capture listener queues a
          ;; click on unhydrated Suspense content. Observe intent only inside this
          ;; owned root; never cancel the event or mutate its markup.
          intent! (fn [event]
                    (when (.contains element (.-target event)) (fire!)))
          events (cond-> []
                   (some modes [:view :hover :click]) (conj "focusin")
                   (or (modes :click) (modes :view)) (conj "click")
                   (or (modes :hover) (modes :view)) (conj "pointerenter"))]
      (doseq [event events] (.addEventListener js/document event intent! true))
      (when (modes :view)
        (observe!)
        (.addEventListener js/window "resize" observe!))
      (when (modes :immediate) (fire!))
      (fn []
        (reset! *active? false)
        (when-let [state @*visibility] ((:stop! state)))
        (.removeEventListener js/window "resize" observe!)
        (doseq [event events] (.removeEventListener js/document event intent! true))))))
