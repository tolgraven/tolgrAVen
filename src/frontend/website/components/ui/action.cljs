(ns website.components.ui.action
  "Native icon actions with transient, clipping-safe hover and keyboard labels."
  (:require [website.component.registry]
            [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.listener :as listener]
            [website.components.portal :as portal]))

(def action-schema
  [:map
   [:label :string]
   [:?class :string]
   [:?href :string]
   [:?on-click fn?]
   [:?aria-pressed :boolean]
   [:?aria-expanded :boolean]])

(defn- label-position [anchor element]
  (let [bounds (.getBoundingClientRect anchor)
        unit (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))
        above? (> (+ (.-bottom bounds) (* 3 unit)) (.-innerHeight js/window))
        maximum-right (max 0.5 (- (/ (.-innerWidth js/window) unit)
                                  (/ (.-width (.getBoundingClientRect element)) unit) 0.5))]
    {:right (min (max 0.5 (/ (- (.-innerWidth js/window) (.-right bounds)) unit)) maximum-right)
     :top (/ (if above? (.-top bounds) (.-bottom bounds)) unit)
     :above? above?}))

(defn- moving-anchor? [anchor]
  (some (fn [element]
          (when (.-getAnimations element)
            (some (fn [animation]
                    (and (= "running" (.-playState animation))
                         (some-> (.-effect animation) .getComputedTiming .-endTime js/isFinite)))
                  (array-seq (.getAnimations element)))))
        (take-while some? (iterate #(.-parentElement %) anchor))))

(defc ^:private <label>
  "Mounted only while its action is hovered or focused; owns dismissal listeners."
  [{:keys [id label anchor]} close!]
  (let [*element (r/use-ref nil)
        [position set-position!] (r/use-state {:right 0.5, :top 0, :above? false})]
    ;; A focus/hover rule can move the action after its event. Follow only that
    ;; finite native motion; stop sampling once settled and cancel on dismissal.
    ;; React owns the measured position and the existing portal markup.
    (r/use-layout-effect
      (fn []
        (when-let [element (.-current *element)]
          (let [*frame (atom nil)
                deadline (+ (js/performance.now) 10000)
                update! #(when (.-isConnected anchor) (set-position! (label-position anchor element)))
                observer (when (exists? js/ResizeObserver) (js/ResizeObserver. update!))]
            (letfn [(follow! [_]
                      (update!)
                      (when (and (.-isConnected anchor) (< (js/performance.now) deadline)
                                 (moving-anchor? anchor))
                        (reset! *frame (js/requestAnimationFrame follow!))))]
              (update!)
              ;; The first frame also catches transitions starting after layout.
              (reset! *frame (js/requestAnimationFrame follow!)))
            (when observer
              (.observe observer element)
              ;; Sibling content can move a fixed-size action without animating
              ;; the action or its ancestors. Observe their changing layout too.
              (doseq [ancestor (take-while some? (iterate #(.-parentElement %) anchor))]
                (.observe observer ancestor)))
            #(do (when @*frame (js/cancelAnimationFrame @*frame))
                 (when observer (.disconnect observer))))))
      [anchor label])
    (r/use-effect
      #(listener/listen! :action-label
         [{:target "window", :event "scroll", :capture? true, :handler close!}
          {:target "window", :event "resize", :handler close!}])
      [close!])
    [portal/<portal> js/document.body
     [:span.action-label
      {:id id
       :ref *element
       :role "tooltip"
       :class (when (:above? position) "action-label--above")
       :style {:right (str (:right position) "rem"), :top (str (:top position) "rem")}}
      label]]))

(defc <action>
  "A native link or button whose label follows hover/focus and dismisses on Escape.
   Attributes stay on the action; the label portals out of ancestor clipping."
  {:spec false}
  [{:keys [label href] :as attrs} :- action-schema content :- vector?]
  :let [*hover? (r/atom false)
        *focus? (r/atom false)
        *dismissed? (r/atom false)
        *anchor (r/atom nil)
        capture-anchor! #(reset! *anchor (.-currentTarget %))]
  (let [id (r/use-id)
        open? (and (or @*hover? @*focus?) (not @*dismissed?) @*anchor)]
    [:<>
     [(if href :a :button)
      (merge (dissoc attrs :label)
             {:type (when-not href "button")
              :aria-label label
              :aria-describedby (when open? id)
              :on-pointer-enter #(do (capture-anchor! %) (reset! *hover? true) (reset! *dismissed? false))
              :on-pointer-leave #(reset! *hover? false)
              :on-focus #(do (capture-anchor! %) (reset! *focus? true) (reset! *dismissed? false))
              :on-blur #(reset! *focus? false)
              :on-key-down #(when (= "Escape" (.-key %)) (reset! *dismissed? true))})
      content]
     (when open?
       [<label> {:anchor @*anchor, :id id, :label label} #(reset! *dismissed? true)])]))
