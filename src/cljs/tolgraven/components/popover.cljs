(ns tolgraven.components.popover
  (:require
    [reagent.core :as r]
    [tolgraven.components.portal :as portal]))

(def anchor-name "--active-popover-anchor")

(defn <popover>
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
    (r/create-class
      {:display-name "Popover"
       :component-did-mount (fn [_] (.addEventListener js/window "resize" measure!))
       :component-will-unmount (fn [_]
                                 (when observer (.disconnect observer))
                                 (.removeEventListener js/window "resize" measure!))
       :reagent-render
       (fn [{:keys [aria-label class expanded? on-click on-pointer-enter
                    on-pointer-leave on-key-down on-focus on-blur open?]}
            content]
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
              [:div.popover__surface content]]]]))})))
