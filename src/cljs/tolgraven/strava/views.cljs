(ns tolgraven.strava.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.views-common :as views]
   [tolgraven.util :as util :refer [at]]))

(declare activity-map)

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
      [:<>
       [:div.strava-activity
       (if @opened?
         [:div.strava-activity-dot ;.section-with-media-bg-wrapper
          {:style {:position :absolute
                   :left 0 :bottom 0
                   :width "100%" :height "100%"
                   :z-index 10
                   :color "var(--fg-2)"
                   :background "linear-gradient(0deg,
                                rgba(135, 48, 1, 0.7),
                                rgba(145, 58, 3, 0.8))"
                   :border "var(--line-width-sm) solid #fc4c02"
                   :box-shadow "3px 3px 15px 10px inset rgba(252, 76, 2, 0.1)"
                   :border-radius "0%"}
           :on-click #(reset! opened? false)}
          
          (let [details @(rf/subscribe [:strava/content [:activity (:id activity)]])]
          [:div.strava-activity-full
           {:ref #(when %
                    (rf/dispatch [:strava/fetch-stream (:id activity) "latlng"])
                    (rf/dispatch [:strava/fetch-activity (:id activity)]))}
           
           [:h2 [:b (:name activity)]]
           [:div.flex
            [:div (:description details)]
            [:div (:name (get @(rf/subscribe [:strava/content [:gear]])
                           (:gear_id activity)))] ]
           
           [:div.strava-activity-stats.flex
            [ui/appear-anon "opacity extra-slow"
             [:div
              {:style {:color "var(--fg-5)"}}
              [:p "Relative effort"]
              [:p "Watts"]
              [:p "Average speed"]
              [:p "Distance"]
              (when (:average_heartrate activity)
                [:p "Heartrate"])
              (when (:kudos_count details)
                [:p "Kudos"])]]
            [:div
             [:p (:suffer_score activity)]
             [:div [:p (:average_watts activity)]]
             [:p (util/format-number (* 3.6 (:average_speed activity)) 1) " km/h"]
             [:p (util/format-number (/ (:distance activity) 1000) 1) " km"]
             (when-let [hr (:average_heartrate activity)]
               [:p hr " bpm"])
             (when-let [kudos (:kudos_count details)]
               [:p  (repeat kudos "*")])]
            
            (when (pos? (-> details :photos :count)) ;TODO small thumbnail clickable with zoom-to-modal
              (let [item [:img {:src (-> details :photos :primary :urls :600)
                                :style {:object-fit "cover"
                                        :max-width "90%"
                                        :margin-left "var(--space-lg)" }}]] 
                [:div
                 {:style {:max-width "30%"
                          :margin-left "var(--space-lg)" }
                  :on-click (fn [e] (.stopPropagation e)
                              (rf/dispatch [:modal-zoom :fullscreen :open
                                            (util/add-attrs item
                                                            {:style {:max-width "100%"}})]))}
                 item]))
            ; [:div "segments"] ;TODO list of segment achievments
             ]
           
           ; (when-let [photos  (:photos details)] ;TODO small thumbnail clickable with zoom-to-modal
           ;     [:img.media-as-bg {:src (-> photos :primary :urls :600)
           ;            :style {:width "100%"
           ;                    :z-index -1}}])
           ])]
         
         [:div.strava-activity-dot
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
          [:span (:average_watts activity) " watts"]])]
       (when @opened?
         [activity-map activity])])))



(defn activities-graph "List multiple activities, currently as a graph from watts and RE"
  []
  (let [height "18em"
        num-activities 30
        activities @(rf/subscribe [:content [:strava :activities]])
        watts-high (apply max (map :average_watts activities))]
    [ui/seen-anon "opacity extra-slow"
     [:div.strava-activities
      {:style {:position :relative
               :height height}}
      (map-indexed 
       (fn [i activity]
         [activity-dot activity i num-activities watts-high])
       (reverse activities))]]))


(defn activity-map "Visualize latlng somehow! Currently goes behind activity-full but putting it on bg would be best (fix latlng so fits...)"
  [activity]
  (let [activity @(rf/subscribe [:strava/activity-stream (:id activity) 5])
        [lats lngs] (map #(map % activity) [first second])
        [lat-max lat-min] (map #(apply % lats) [max min])
        [lng-max lng-min] (map #(apply % lngs) [max min]) ]
    [:div
     {:style {:position :relative
              :top  "5%" :left "5%"
              :height "90%" :width "90%"}}
     (if activity
       [ui/appear-anon "opacity extra-slow"
        (for [[lat lng] activity]
         [:span
          {:style {:position :absolute
                   :bottom (str (* 100 (/ (- lat lat-min) (- lat-max lat-min))) "%")
                   :left (str (* 100 (/ (- lng lng-min) (- lng-max lng-min))) "%")
                   :height "3px" :width "4px"
                   :background "rgba(246, 255, 245, 0.3)"}} ])]
       [views/loading-spinner true :massive]) ]))


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
     (if athlete
       [:div.strava-profile.flex
        [:img.strava-profile-image.user-avatar
         {:src (:profile_medium athlete)
          :width "50%" :height "50%"}]
        [:div.strava-athlete ;.flex
         [:h3 (:firstname athlete) " " (:lastname athlete)]
         [:div (:bio athlete)]
         [:div (:city athlete)]
         [:div (:weight athlete) " kg"]]
        [:div.strava-story (:story data)]]
       [views/loading-spinner true])
      
     (if stats
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
        [totals-stats stats :recent_ride_totals "Recent"] ]
       [views/loading-spinner true :massive])

     [activities-graph] ]))

