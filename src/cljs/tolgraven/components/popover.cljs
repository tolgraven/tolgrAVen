(ns tolgraven.components.popover
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [react :as react]
    [reagent.core :as r]
    [tolgraven.components.portal :as portal]))

(def anchor-name "--active-popover-anchor")

(defc <popover>
  "Generic anchored content which can transition into a full-viewport surface."
  [_ _]
  (let [*element (atom nil)
        *scale (r/atom nil)
        measure! (fn []
                   (when-let [element @*element]
                     ;; Fill the preview width and crop the page vertically. Fitting
                     ;; both axes makes portrait previews tiny and leaves a blank strip.
                     (reset! *scale (/ (.-clientWidth element) (.-innerWidth js/window)))))
        observer (when (exists? js/ResizeObserver) (js/ResizeObserver. measure!))
        set-element! (fn [element]
                       (when observer (.disconnect observer))
                       (reset! *element element)
                       (when element
                         (when observer (.observe observer element))
                         (measure!)))]
    (fn [{:keys [aria-label class expanded? on-click on-pointer-enter
                    on-pointer-leave on-key-down on-focus on-blur open?]}
            content]
      (react/useEffect
       (fn []
         (.addEventListener js/window "resize" measure!)
         #(do (when observer (.disconnect observer))
              (.removeEventListener js/window "resize" measure!))) #js [])
         (when open?
           [portal/<portal> js/document.body
            [:div.popover-layer
             {:class (when expanded? "popover-layer--expanded")}
             [:div.popover
              {:aria-label aria-label
               :class (str class (when expanded? " popover--expanded"))
               :data-popover true
               :style (when @*scale {"--popover-content-scale" @*scale})
               :tab-index 0
               :on-key-down on-key-down
               :on-focus on-focus
               :on-blur on-blur
               :on-click (fn [event]
                           (.stopPropagation event)
                           (when on-click (on-click event)))
               :on-pointer-enter on-pointer-enter
               :on-pointer-leave on-pointer-leave
               :ref set-element!
               :role "dialog"}
              [:div.popover__surface content]]]]))))
