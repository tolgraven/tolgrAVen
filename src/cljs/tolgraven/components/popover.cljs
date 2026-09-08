(ns tolgraven.components.popover
  (:require
    [tolgraven.components.portal :as portal]))

(def ^:private viewport-padding 12)
(def ^:private anchor-gap 10)

(defn- clamp [value minimum maximum]
  (max minimum (min value maximum)))

(defn- position
  [{:keys [bottom left right top]} width height]
  (let [viewport-width (.-innerWidth js/window)
        viewport-height (.-innerHeight js/window)
        below? (or (<= (+ bottom anchor-gap height viewport-padding)
                       viewport-height)
                   (< bottom (- viewport-height top)))
        x (clamp (- (/ (+ left right) 2) (/ width 2))
                 viewport-padding
                 (max viewport-padding
                      (- viewport-width width viewport-padding)))
        y (if below?
            (+ bottom anchor-gap)
            (- top height anchor-gap))]
    {:left (str x "px")
     :top (str (clamp y
                      viewport-padding
                      (max viewport-padding
                           (- viewport-height height viewport-padding)))
               "px")}))

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
                 (merge (position anchor-rect width height)
                        {:height (str height "px")
                         :width (str width "px")}))}
       content]]]))
