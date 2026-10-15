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

(defc ^:private <label>
  "Mounted only while its action is hovered or focused; owns dismissal listeners."
  [{:keys [id label right top above?]} close!]
  (r/use-effect
    #(listener/listen! :action-label
       [{:target "window", :event "scroll", :capture? true, :handler close!}
        {:target "window", :event "resize", :handler close!}])
    [close!])
  [portal/<portal> js/document.body
   [:span.action-label
    {:id id
     :role "tooltip"
     :class (when above? "action-label--above")
     :style {:right (str right "rem"), :top (str top "rem")}}
    label]])

(defc <action>
  "A native link or button whose label follows hover/focus and dismisses on Escape.
   Attributes stay on the action; the label portals out of ancestor clipping."
  {:spec false}
  [{:keys [label href] :as attrs} :- action-schema content :- vector?]
  :let [*hover? (r/atom false)
        *focus? (r/atom false)
        *dismissed? (r/atom false)
        *anchor (r/atom nil)
        capture-anchor! (fn [event]
                          ;; Measure at the native interaction boundary, not during render.
                          (let [rect (.getBoundingClientRect (.-currentTarget event))
                                unit (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))
                                above? (> (+ (.-bottom rect) (* 3 unit)) (.-innerHeight js/window))]
                            (reset! *anchor {:right (max 0.5 (/ (- (.-innerWidth js/window) (.-right rect)) unit))
                                             :top (/ (if above? (.-top rect) (.-bottom rect)) unit)
                                             :above? above?})))]
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
       [<label> (assoc @*anchor :id id :label label) #(reset! *dismissed? true)])]))
