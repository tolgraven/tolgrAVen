(ns tolgraven.strava.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util :refer [at]]))

(defn totals-stats
  [athlete category heading]
  (let [stats (category athlete)
        distance (-> (:distance stats)
                     (/ 1000)
                     (util/format-number 2))
        hours (/ (:moving_time stats) 3600)]
    [:div
     [:h3 heading]
     [:div  (:count stats)]
     [:div distance [:span " km"]]
     [:div (util/format-number hours 1) [:span " hours"]]
     [:div (util/format-number (/ distance (:count stats)) 2) [:span " km"]]
     [:div (util/format-number (/ distance hours) 1) [:span " km/h"]]]))

(defn activity-dot
  [activity i num-total watts-high]
  (let [hovered? (r/atom false)
        opened? (r/atom false) ;like above but lock with click
        cutoff 80
        size (str (max 0.5 (* 1.5 (/ (:suffer_score activity) 300))) "em")]
    (fn [activity i num-total watts-high]
      [:div.strava-activity
       (if @opened?
         [:span.strava-activity-dot
          {:style {:position :absolute
                   :left 0 :bottom 0
                   :width "90%" :height "100%"
                   :z-index 10
                   
                   :color "var(--fg-2)"
                   :background "linear-gradient(0deg,
                                rgba(135, 48, 1, 0.7),
                                rgba(145, 58, 3, 0.8))"
                   :border "var(--line-width-sm) solid #fc4c02"
                   :box-shadow "3px 3px 15px 10px inset rgba(252, 76, 2, 0.1)"
                   ; :background "rgba(252, 76, 2, 0.4)"
                   ; :box-shadow "none"
                   :border-radius "5%"}
           :on-click #(reset! opened? false)}
          [:div.strava-activity-full
           [:h3 [:b (:name activity)]]
           [:div.flex
            [ui/appear-anon "opacity extra-slow"
             [:div
              {:style {:color "var(--fg-5)"}}
              [:p "Relative effort"]
              [:p "Watts"]
              [:p "Average speed"]
              [:p "Heartrate"]]]
           [:div
            [:p (:suffer_score activity)]
            [:p (:average_watts activity)]
            [:p (util/format-number (* 3.6 (:average_speed activity)) 1) " km/h"]
            [:p (:average_heartrate activity) " bpm"]]]]]
         
         [:span.strava-activity-dot
          {:style {:position :absolute
                   :left (str (* 100 (/ i num-total)) "%")
                   :bottom (str (* 100 (/ (- (:average_watts activity) cutoff)
                                          (- watts-high cutoff))) "%")
                   :width size :height size}
           :on-mouse-enter #(reset! hovered? true)
           :on-mouse-leave #(reset! hovered? false)
           :on-click #(do (swap! opened? not)
                          (reset! hovered? false))}])
       (when @hovered?
         [:div.strava-activity-details
          [:span [:b (:name activity)]]
          [:span (:suffer_score activity) " relative effort "]
          [:span (:average_watts activity) " watts"]])
       ])))



(defn activities-graph "List multiple activities, currently as a graph from watts and RE"
  []
  (let [height "12em"
        num-activities 30
        activities @(rf/subscribe [:content [:strava :activities]])
        watts-high (apply max (map :average_watts activities))]
    [:div.strava-activities
     {:style {:position :relative
              :height height}}
     (map-indexed 
       (fn [i activity]
         [activity-dot activity i num-activities watts-high])
       (reverse activities))]))

(defn strava "Make an increasingly fancy visualizer feed thingy for practice"
  []
  (let [data @(rf/subscribe [:content [:strava]])
        stats (:stats data)
        athlete (:athlete data)
        recent (:recent_ride_totals stats)
        distance (-> (:distance recent)
                     (/ 1000)
                     (util/format-number 2))
        arrow (fn [direction]
                [:i.fa {:class (str "fa-arrow-" (name direction))
                        :style {:color "#fc4c02"}}])]
    [:section.strava.section-with-media-bg-wrapper.covering-2
     [:img.media-as-bg {:src "img/strava-heatmap-3.png"}]
     [:h1  [:img {:src "img/strava_logo_nav.png"}]]
     [:div.strava-profile.flex
      [:img.strava-profile-image.user-avatar
        {:src (:profile_medium athlete)
         :width "50%" :height "50%"}]
      [:div.strava-athlete ;.flex
       [:h3 (:firstname athlete) " " (:lastname athlete)]
       [:div (:bio athlete)]
       [:div (:city athlete)]
       [:div (:weight athlete) " kg"]]
      ; [:div.center-content [:img {:src "img/strava_logo_nav.png"}]]
      ]
      
     [:div.strava-stats.flex
      [:div
       [:h3 [:img {:src "img/strava-icon.png"
                   :style {:width "1.25em"}}]]
       [:div "Rides"]
       [:div "Total distance"]
       [:div "Time"]
       [:div "Average distance"]
       [:div "Average speed"]]
      [totals-stats stats :all_ride_totals "Total"] 
      [totals-stats stats :recent_ride_totals "Last month"]
      ; [:div
      ;  [ui/seen-anon "slide-in" [arrow :up]] ;XXX fix actual comparison
      ;  [ui/seen-anon "slide-in" [arrow :up]]]
      ]

     [activities-graph]
     ; [:pre {} (first (:activities data))]
     ]))

