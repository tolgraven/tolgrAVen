(ns tolgraven.components.popover
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.react :as react]
    [reagent.core :as r]
    [tolgraven.components.portal :as portal]))

(def anchor-name "--active-popover-anchor")

(defc <popover>
  "Generic anchored content which can transition into a full-viewport surface."
  [{:keys [aria-label class expanded? on-click on-pointer-enter
                    on-pointer-leave on-key-down on-focus on-blur open? scaled?]
             :or {scaled? true}}
   content]
  :let [*element (atom nil)
        *scale (r/atom nil)
        capture! (fn [element] (reset! *element element) nil)]
  ;; Measurement belongs to the committed open surface. Closed/abandoned renders
  ;; allocate no observer/listener; reopening and StrictMode acquire fresh owners.
  (react/use-layout-effect
    (fn []
      (when (and open? scaled? (exists? js/window) @*element)
        (let [element @*element
              measure! #(reset! *scale (/ (.-clientWidth element) (.-innerWidth js/window)))
              observer (when (exists? js/ResizeObserver) (js/ResizeObserver. measure!))]
          (measure!)
          (when observer (.observe observer element))
          (.addEventListener js/window "resize" measure!)
          (fn []
            (when observer (.disconnect observer))
            (.removeEventListener js/window "resize" measure!)))))
    [open? scaled?])
         (when open?
           [portal/<portal> js/document.body
            [:div.popover-layer
             {:class (when expanded? "popover-layer--expanded")}
             [:div.popover
              {:aria-label aria-label
               :class (str class
                           (when-not scaled? " popover--readable")
                           (when expanded? " popover--expanded"))
               :data-popover true
               :style (when (and scaled? @*scale) {"--popover-content-scale" @*scale})
               :tab-index 0
               :on-key-down on-key-down
               :on-focus on-focus
               :on-blur on-blur
               :on-click (fn [event]
                           (.stopPropagation event)
                           (when on-click (on-click event)))
               :on-pointer-enter on-pointer-enter
               :on-pointer-leave on-pointer-leave
               :ref capture!
               :role "dialog"}
              [:div.popover__surface content]]]]))
