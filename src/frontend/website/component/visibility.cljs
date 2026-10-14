(ns website.component.visibility
  "Visibility effects for a component's existing native root. React owns markup;
   this lifecycle adapter owns the observer, delay and teardown."
  (:require [website.react :as r]
            [website.component.registry]
            [website.macros :refer-macros [defc]]))

(defn setup [{:keys [event callback on-change once? threshold root-margin delay-ms]
              :or {once? true threshold 0.5 root-margin "0px" delay-ms 0}}]
  (when (and (or event callback on-change) (exists? js/window))
    (let [fire! (or callback (when event #(r/dispatch event)))
          *observer (atom nil)
          *timer (atom nil)
          *active? (atom true)
          *fired? (atom false)
          observe! (fn [element]
                     (when @*active?
                       (if (exists? js/IntersectionObserver)
                         (let [observer (js/IntersectionObserver.
                                          (fn [entries observer]
                                            (when @*active?
                                              (doseq [entry (array-seq entries)]
                                                (let [visible? (and (.-isIntersecting entry)
                                                                    (>= (.-intersectionRatio entry) threshold))]
                                                  (when on-change (on-change visible?))
                                                  (when (and visible? (or (not once?) (not @*fired?)))
                                                    (reset! *fired? true)
                                                    (when once? (.disconnect observer))
                                                    (when fire! (fire!)))))))
                                          (clj->js {:threshold threshold :rootMargin root-margin}))]
                           (reset! *observer observer)
                           (.observe observer element))
                         (do (when on-change (on-change true))
                             (when fire! (fire!))))))]
      {:mount! (fn [element]
                 (if (pos? delay-ms)
                   (reset! *timer (js/setTimeout #(observe! element) delay-ms))
                   (observe! element)))
       :stop! (fn []
                (reset! *active? false)
                (when @*timer (js/clearTimeout @*timer))
                (when @*observer (.disconnect @*observer)))})))

(def feature
  {:setup setup
   :mount (fn [state element] ((:mount! state) element))
   :unmount (fn [state] ((:stop! state)))})


(defc <sentinel>
  "Lean sentinel for source adapters that cannot import the full component
   runtime (component.sources imports them). Normal UI uses defc :on-seen.
   Both paths share setup; this boundary never owns another observer algorithm."
  {:spec false}
  [options]
  (let [*element (r/use-ref nil)
        *owner (r/use-ref nil)]
    (r/use-layout-effect
      (fn []
        (fn []
          (let [old (.-current *owner)]
            (set! (.-current *owner) nil)
            (when-let [state (:state old)] ((:stop! state)))))) [])
    (r/use-layout-effect
      (fn []
        (let [element (.-current *element)
              old (.-current *owner)]
          (when (or (not= options (:options old)) (not= element (:element old)))
            (when-let [state (:state old)] ((:stop! state)))
            (let [state (when element (setup options))]
              (set! (.-current *owner) {:options options, :element element, :state state})
              (when state ((:mount! state) element)))))
        nil))
    [:div {:aria-hidden true
           :ref (fn [element] (set! (.-current *element) element))
           :style {:height "0.0625rem"
                   :margin-bottom "-0.0625rem"
                   :pointer-events "none"}}]))
