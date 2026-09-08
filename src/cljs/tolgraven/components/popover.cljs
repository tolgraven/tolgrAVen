(ns tolgraven.components.popover
  (:require
    [tolgraven.components.portal :as portal]))

(def ^:private viewport-padding 12)
(def ^:private anchor-gap 10)

(defn- clamp [value minimum maximum]
  (max minimum (min value maximum)))

(defn- collapsed-style
  [{:keys [bottom left right top]} width height]
  (let [viewport-width (.-innerWidth js/window)
        viewport-height (.-innerHeight js/window)
        scale (min 1
                   (/ (min width (- viewport-width (* 2 viewport-padding)))
                      viewport-width)
                   (/ (min height (- viewport-height (* 2 viewport-padding)))
                      viewport-height))
        preview-width (* viewport-width scale)
        preview-height (* viewport-height scale)
        below? (or (<= (+ bottom anchor-gap preview-height viewport-padding)
                      viewport-height)
                  (< bottom (- viewport-height top)))
        x (clamp (- (/ (+ left right) 2) (/ preview-width 2))
                viewport-padding
                (max viewport-padding
                     (- viewport-width preview-width viewport-padding)))
        y (if below?
            (+ bottom anchor-gap)
            (- top preview-height anchor-gap))
        y (clamp y
                viewport-padding
                (max viewport-padding
                     (- viewport-height preview-height viewport-padding)))]
    {:transform (str "translate3d(" x "px, " y "px, 0) "
                    "scale(" scale ")")}))

(defn <popover>
  "Render content in a portal, positioned next to an anchor rectangle."
  [{:keys [anchor-rect aria-label class expanded? height on-click on-pointer-enter
           on-pointer-leave open? width]
    :or {height 300
         width 480}}
   content]
  (when (and open? anchor-rect)
    [portal/<portal> "popover-portal"
     [:div.popover-layer
      {:class (when expanded? "popover-layer--expanded")}
      [:div.popover
       {:class (str class
                    (when expanded? " popover--expanded"))
        :data-link-preview-popover true
        :aria-label aria-label
        :on-click (fn [event]
                    (.stopPropagation event)
                    (when on-click (on-click event)))
        :on-pointer-enter on-pointer-enter
        :on-pointer-leave on-pointer-leave
        :role "dialog"
        :style (when-not expanded?
                 (collapsed-style anchor-rect width height))}
       content]]]))
