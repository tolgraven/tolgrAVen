(ns tolgraven.component.visibility
  "Visibility effects for a component's existing native root. React owns markup;
   this lifecycle adapter owns the observer, delay and teardown."
  (:require [tolgraven.react :as rf]))

(defn setup [{:keys [event callback once? threshold root-margin delay-ms]
              :or {once? true threshold 0.5 root-margin "0px" delay-ms 0}}]
  (when (and (or event callback) (exists? js/window))
    (let [fire! (or callback #(rf/dispatch event))
          *observer (atom nil)
          *timer (atom nil)
          *active? (atom true)
          *fired? (atom false)
          observe! (fn [element]
                     (when @*active?
                       (if (exists? js/IntersectionObserver)
                         (let [observer (js/IntersectionObserver.
                                          (fn [entries observer]
                                            (when (and @*active? (or (not once?) (not @*fired?))
                                                       (some #(and (.-isIntersecting %)
                                                                   (>= (.-intersectionRatio %) threshold))
                                                             (array-seq entries)))
                                              (reset! *fired? true)
                                              (when once? (.disconnect observer))
                                              (fire!)))
                                          (clj->js {:threshold threshold :rootMargin root-margin}))]
                           (reset! *observer observer)
                           (.observe observer element))
                         (fire!))))]
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
