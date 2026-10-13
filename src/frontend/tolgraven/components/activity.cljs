(ns tolgraven.components.activity
  (:require [tolgraven.macros :refer-macros [defc]]
            [tolgraven.components.activity-schema :as schema]))

(defc <calendar>
  "Provider days in chronological order; a blank cell is never a fabricated count."
  [days :- schema/calendar]
  (let [offset (if-let [date (:date (first days))]
                 (.getUTCDay (js/Date. (str date "T00:00:00Z"))) 0)]
    [:div.activity-calendar
     [:div.activity-calendar__scroll {:tab-index 0, :aria-label "Daily activity calendar"}
      [:div.activity-calendar__grid {:role "list"}
       (for [i (range offset)]
         ^{:key (str "padding-" i)} [:span.activity-calendar__padding {:aria-hidden true}])
       (for [{:keys [date count level]} days
             :let [label (str count " contributions on " date)]]
         ^{:key date}
         [:span.activity-calendar__day {:role "listitem"
                                       :data-date date
                                       :data-level level
                                       :title label
                                       :aria-label label}])]]
     [:div.activity-calendar__legend
      [:span (str (reduce + (map :count days)) " contributions")]
      [:span "Less"]
      (for [level (range 5)]
        ^{:key level} [:span.activity-calendar__day {:data-level level, :aria-hidden true}])
      [:span "More"]]]))
