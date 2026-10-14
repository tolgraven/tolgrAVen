(ns website.components.content.resize
  "A local pointer gesture previews geometry; its final callback owns persistence."
  (:require [website.component.registry]
            [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.components.content.layout :as layout]))

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

(defn edge-geometry [edge original measured dx dy]
  (let [result (geometry original measured (if (= edge :left) (- dx) dx)
                          (if (= edge :top) (- dy) dy))]
    (case edge
      (:left :right) (merge (select-keys original [:width :height]) (select-keys result [:width]))
      (:top :bottom) (merge (select-keys original [:width :height]) (select-keys result [:height]))
      result)))

(defc <handle>
  "Drag an edge or use arrow keys to resize a content widget in its chosen units."
  {:spec-schema [:map [:target fn?] [:geometry layout/schema] [:preview fn?] [:commit fn?] [:?parent-width fn?]
                      [:?edge [:enum :left :right :top :bottom]] [:?class :string]]}
  [{:keys [target preview commit parent-width edge class] original :geometry}]
  :let [*gesture (atom nil)]
  (r/use-effect
    (fn []
      (fn []
        (when-let [{:keys [element pointer]} @*gesture]
          (when (.hasPointerCapture element pointer) (.releasePointerCapture element pointer)))
        (reset! *gesture nil))) [])
  [:button.md-resize-handle
   {:type "button"
    :class class
    :data-edge (some-> edge name)
    :aria-label (if edge (str "Resize widget " (name edge) " edge") "Resize widget")
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
        (preview (edge-geometry edge original measured (- (.-clientX event) x) (- (.-clientY event) y)))))
    :on-pointer-up
    (fn [event]
      (when-let [{:keys [x y measured element pointer]} @*gesture]
        (let [result (edge-geometry edge original measured (- (.-clientX event) x) (- (.-clientY event) y))]
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
            (commit (edge-geometry edge original measured (* step dx) (* step dy)))))))}
   (if edge "" "↘")])
