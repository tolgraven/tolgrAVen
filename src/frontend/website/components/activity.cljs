(ns website.components.activity
  (:require [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.listener :as listener]
            [website.components.portal :as portal]
            [website.components.activity-schema :as schema]))

(defn- anchor [event day]
  ;; Native geometry is read only at the hover/focus boundary; React owns the UI.
  (let [rect (.getBoundingClientRect (.-currentTarget event))
        unit (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))
        above? (> (+ (.-bottom rect) (* 12 unit)) (.-innerHeight js/window))]
    (assoc day :left (/ (+ (.-left rect) (.-right rect)) (* 2 unit))
               :top (/ (if above? (.-top rect) (.-bottom rect)) unit)
               :above? above?)))

(defn- move-focus! [event]
  (let [cell (.-currentTarget event)
        cells (vec (array-seq (.querySelectorAll (.-parentElement cell) "[data-date]")))
        index (first (keep-indexed #(when (identical? %2 cell) %1) cells))
        destination (case (.-key event)
                      "ArrowUp" (dec index)
                      "ArrowDown" (inc index)
                      "ArrowLeft" (- index 7)
                      "ArrowRight" (+ index 7)
                      "Home" 0
                      "End" (dec (count cells))
                      nil)]
    (when (some? destination)
      (let [target (nth cells (max 0 (min (dec (count cells)) destination)))]
        (.preventDefault event)
        (.focus target #js {:preventScroll true})
        (.scrollIntoView target #js {:block "nearest", :inline "nearest"})))))

(defc <calendar-popup>
  "A transient, pointer-transparent tooltip. Unmounted immediately on exit."
  [{:keys [date count left top above? id]} details close!]
  (r/use-effect
    #(listener/listen! :activity-tooltip
       [{:target "window", :event "resize", :handler close!}
        {:target "window", :event "scroll", :capture? true, :handler close!}])
    [close!])
  [portal/<portal> js/document.body
   [:div.activity-calendar__popup
    {:role "tooltip"
     :id id
     :class (when above? "activity-calendar__popup--above")
     :style {:left (str "clamp(min(10rem, 50vw), " left "rem, calc(100vw - min(10rem, 50vw)))")
             :top (str top "rem")}}
    [:time {:date-time date} date]
    [:strong (str count " contributions")]
    (when (and details (pos? count)) (conj details date))]])

(defc <calendar>
  "Provider days in chronological order; a blank cell is never a fabricated count."
  [days :- schema/calendar & [details]]
  :let [*active (r/atom nil)]
  (let [tooltip-id (r/use-id)
        offset (if-let [date (:date (first days))]
                 (.getUTCDay (js/Date. (str date "T00:00:00Z"))) 0)]
    [:div.activity-calendar
     [:div.activity-calendar__scroll
      {:tab-index 0
       :aria-label "Daily activity calendar"
       :on-scroll #(reset! *active nil)
       :on-pointer-leave #(reset! *active nil)
       :on-blur #(reset! *active nil)}
      [:div.activity-calendar__grid {:role "list"}
       (for [i (range offset)]
         ^{:key (str "padding-" i)} [:span.activity-calendar__padding {:aria-hidden true}])
       (for [{:keys [date count level] :as day} days
             :let [label (str count " contributions on " date)]]
         ^{:key date}
         [:span.activity-calendar__day {:role "listitem"
                                       :data-date date
                                       :data-level level
                                       :tab-index (if (= date (or (:date @*active) (:date (first days)))) 0 -1)
                                       :aria-describedby (when (= date (:date @*active)) tooltip-id)
                                       :on-pointer-enter #(reset! *active (anchor % day))
                                       :on-focus #(reset! *active (anchor % day))
                                       :on-key-down #(if (= "Escape" (.-key %))
                                                       (reset! *active nil)
                                                       (move-focus! %))
                                       :aria-label label}])]]
     (when @*active [<calendar-popup> (assoc @*active :id tooltip-id) details #(reset! *active nil)])
     [:div.activity-calendar__legend
      [:span (str (reduce + (map :count days)) " contributions")]
      [:span "Less"]
      (for [level (range 5)]
        ^{:key level} [:span.activity-calendar__day {:data-level level, :aria-hidden true}])
      [:span "More"]]]))
