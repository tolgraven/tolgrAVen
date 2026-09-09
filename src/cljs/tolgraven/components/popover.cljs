(ns tolgraven.components.popover
  (:require
    [tolgraven.components.portal :as portal]))

(def anchor-name "--active-popover-anchor")

(defn <popover>
  "Generic anchored content which can transition into a full-viewport surface."
  [{:keys [aria-label class expanded? on-click on-pointer-enter
           on-pointer-leave open?]}
   content]
  (when open?
    [portal/<portal> js/document.body
     [:div.popover-layer
      {:class (when expanded? "popover-layer--expanded")}
      [:div.popover
       {:aria-label aria-label
        :class (str class (when expanded? " popover--expanded"))
        :data-popover true
        :on-click (fn [event]
                    (.stopPropagation event)
                    (when on-click (on-click event)))
        :on-pointer-enter on-pointer-enter
        :on-pointer-leave on-pointer-leave
        :ref (fn [element]
               (when element
                 (js/requestAnimationFrame
                   #(let [scale (min (/ (.-clientWidth element)
                                        (.-innerWidth js/window))
                                     (/ (.-clientHeight element)
                                        (.-innerHeight js/window)))]
                      (.setProperty (.-style element)
                                    "--popover-content-scale"
                                    scale)))))
        :role "dialog"}
       [:div.popover__surface content]]]]))
