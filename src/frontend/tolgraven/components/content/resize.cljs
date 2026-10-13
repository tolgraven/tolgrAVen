(ns tolgraven.components.content.resize
  "A local pointer gesture previews geometry; its final callback owns persistence."
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as r]
            [tolgraven.components.content.layout :as layout]))

(defn measurement [element]
  (let [bounds (.getBoundingClientRect element)]
    {:width (.-width bounds)
     :height (.-height bounds)
     :units {:rem (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))
             :em (js/parseFloat (.-fontSize (js/getComputedStyle element)))
             :parent (some-> element .-parentElement .getBoundingClientRect .-width)}}))

(defn geometry [original measured dx dy]
  {:width (layout/resized (:width original) (+ (:width measured) dx) (:units measured))
   :height (layout/resized (:height original) (+ (:height measured) dy) (:units measured))})

(defc <handle>
  "Drag the corner or use arrow keys to resize a content widget in its chosen units."
  {:spec-schema [:map [:target fn?] [:geometry layout/schema] [:preview fn?] [:commit fn?] [:?parent-width fn?]]}
  [{:keys [target preview commit parent-width] original :geometry}]
  :let [*gesture (atom nil)]
  (r/use-effect
    (fn []
      (fn []
        (when-let [{:keys [element pointer]} @*gesture]
          (when (.hasPointerCapture element pointer) (.releasePointerCapture element pointer)))
        (reset! *gesture nil))) [])
  [:button.md-resize-handle
   {:type "button"
    :aria-label "Resize widget"
    :title "Drag to resize; arrow keys resize by one em"
    :on-pointer-down
    (fn [event]
      (when-let [element (target)]
        (.preventDefault event)
        (.stopPropagation event)
        (let [measured (cond-> (measurement element) parent-width (assoc-in [:units :parent] (parent-width)))
              button (.-currentTarget event)
              pointer (.-pointerId event)]
          (.setPointerCapture button pointer)
          (reset! *gesture {:x (.-clientX event)
                            :y (.-clientY event)
                            :measured measured
                            :element button
                            :pointer pointer}))))
    :on-pointer-move
    (fn [event]
      (when-let [{:keys [x y measured]} @*gesture]
        (preview (geometry original measured (- (.-clientX event) x) (- (.-clientY event) y)))))
    :on-pointer-up
    (fn [event]
      (when-let [{:keys [x y measured element pointer]} @*gesture]
        (let [result (geometry original measured (- (.-clientX event) x) (- (.-clientY event) y))]
          (reset! *gesture nil)
          (when (.hasPointerCapture element pointer) (.releasePointerCapture element pointer))
          (commit result)
          (preview nil))))
    :on-pointer-cancel (fn [_] (reset! *gesture nil) (preview nil))
    :on-key-down
    (fn [event]
      (when-let [direction (get {"ArrowLeft" [-1 0], "ArrowRight" [1 0]
                                "ArrowUp" [0 -1], "ArrowDown" [0 1]} (.-key event))]
        (when-let [element (target)]
          (.preventDefault event)
          (.stopPropagation event)
          (let [measured (cond-> (measurement element) parent-width (assoc-in [:units :parent] (parent-width)))
                step (get-in measured [:units :em])
                [dx dy] direction]
            (commit (geometry original measured (* step dx) (* step dy)))))))}
   "↘"])
