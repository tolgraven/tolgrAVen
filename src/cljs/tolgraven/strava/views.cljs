(ns tolgraven.strava.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.views-common :as views]
   [tolgraven.util :as util :refer [at]]
   [cljsjs.react-leaflet]))

(declare activity-map)
(declare activity-map-canvas)
(declare activity-map-leaflet)

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

(defn activity-photo
  [data]
  (when (pos? (:count data))
    (let [item [:img {:src (-> data :primary :urls :600)
                      :style {:object-fit "cover"
                              :max-width "100%" }}]] 
      [:div.strava-activity-photo
       {:on-click (fn [e] (.stopPropagation e)
                    (rf/dispatch [:modal-zoom :fullscreen :open
                                  (util/add-attrs item
                                                  {:style {:max-width "100%"}})]))}
       [ui/appear-anon "zoom"
        item]])))

(defn activity-splits "km splits from activity"
  [{:keys [splits_metric] :as details}]
  (let [num-splits (count splits_metric)
        [min-speed max-speed] (map #(apply % (map :average_speed splits_metric)) [min max]) ]
    [:div.strava-activity-splits.flex
     (map-indexed
      (fn [i {:keys [average_speed] :as split}]
        [:div.strava-activity-split
         {:style {:left (str (* 100 (/ i num-splits)) "%")
                  :bottom (str (* 100 (util/rescale-to-frac average_speed min-speed max-speed)) "%")}
          }
         [:span (util/format-number (* 3.6 (:average_speed split)) 1)]])
      splits_metric)

     ]))

(defn activity-segments "Segments for activity"
  [{:keys [segment_efforts] :as details}]
  [:div.strava-activity-segments
   (for [segment segment_efforts]
     [:div.strava-activity-segment
      [:h4 (:name segment)]
      [:p (util/format-number (* 3.6 (/ (:distance segment) (:elapsed_time segment))) 1) [:span " km/h"]]
      ])])

(defn activity-laps "Laps for activity"
  [{:keys [laps] :as details}]
  [:div.strava-activity-laps
   (for [lap laps]
     [:div.strava-activity-lap
      [:h4 (:name lap)]
      [:p (/ (:distance lap) 1000) [:span " km"]]
      [:p (* 3.6 (:average_speed lap)) [:span " km/h"]]])
   ])

(defn kudo "Strava icon for kudos, show name on hover"
  [kudoer]
  (let [hovered? (r/atom false)]
    (fn [kudoer]
      [:<>
       [:img.strava-kudo-dot
        {:src "img/strava-icon.png"
         :on-mouse-enter #(reset! hovered? true)
         :on-mouse-leave #(reset! hovered? false)} ]
       (when @hovered?
         [:div.strava-kudos-popup
           [:pre (:firstname kudoer) " " (:lastname kudoer)]])])))

(defn kudos "List kudos"
  [activity]
  (let [kudoers @(rf/subscribe [:strava/content [:kudos (:id activity)]])]
    [:div.strava-activity-kudos
     {:style {:position :relative}
      :ref #(when %
              (rf/dispatch [:strava/fetch-kudos (:id activity)]))}
     (for [kudoer kudoers]
       [kudo kudoer]) ]))

(defn activity-stats
  [activity details]
  [:<>
   [:div.flex
    {:style {:justify-content :space-between
             :font-size "90%"}}
    [:div.strava-activity-description (:description details)]
    [:div.strava-activity-gear
     (:name (get @(rf/subscribe [:strava/content [:gear]])
                 (:gear_id activity)))] ]
   [:div.strava-activity-stats.flex
   
   [:div.flex
    [ui/appear-anon "opacity extra-slow"
     [:div.strava-activity-stats-descriptions
      {:style {:color "var(--fg-5)"}}
      (when (:suffer_score activity)
        [:p "Relative effort"])
      [:p "Watts"]
      [:p "Average speed"]
      [:p "Distance"]
      [:p "Elevation gain"]
      (when (:average_heartrate activity)
        [:p "Heartrate"])
      (when (:kudos_count details)
        [:p "Kudos"])
       (when (pos? (:pr_count activity))
        [:p "PRs"])]]
    [:div.strava-activity-stats-numbers
     [:p (:suffer_score activity)]
     [:div [:p (:average_watts activity)]]
     [:p (util/format-number (* 3.6 (:average_speed activity)) 1) [:span " km/h"]]
     [:p (util/format-number (/ (:distance activity) 1000) 1) [:span " km"]]
     [:p (:total_elevation_gain details) [:span " m"]]
     (when-let [hr (:average_heartrate activity)]
       [:p hr [:span " bpm"]])
     (when-let [kudos_count (:kudos_count details)]
       [kudos activity])
     (when (pos? (:pr_count activity))
       [:p (repeat (:pr_count activity)
                    [:i.fa.fa-award.strava-award])])]]

   [activity-photo (:photos details)] ]])


(defn xy-in-rect [e dimension rect]
 (let [m {:x (- (.-clientX e) (.-left rect)) ;XXX shouldnt do unneccessary work tho
          :y (- (.-clientY e) (.-top rect))}]
  (map m dimension))) ;ok so now will return vec even for one dim

(defn draw-graph
  [canvas id data cursor-pos]
  (let [ctx (.getContext canvas "2d")
        [data-max data-min] (map #(apply % data) [max min])
        data-min 0
        [w h] [(.-clientWidth canvas) (.-clientHeight canvas)]
        size (count data)]
    (set! (.-strokeStyle ctx) "rgb(252,136,54)")
    (set! (.-fillStyle ctx) "rgb(252,176,172)")
    (set! (.-lineWidth ctx) 2)
    (.moveTo ctx 0 (- h (* h (util/rescale-to-frac (first data) data-min data-max))))
    
    ; (.fillText ctx "start" 0 0)
    (doall (map-indexed
     (fn [i point]
      (let [x (* w (util/rescale-to-frac i 0 size))
            y (- h (* h (util/rescale-to-frac point data-min data-max)))]
        (.lineTo ctx x y)))
     data))
    (.stroke ctx)))



(defn graph-canvas ""
  [kind activity]
  (let [data @(rf/subscribe [:strava/activity-stream (:id activity) kind 50])
        [data-max data-min] (map #(apply % data) [max min])
        data-size (count data)
        cursor-pos (r/atom nil)
        canvas (r/atom nil)]
    (fn [kind activity]
      [:div.strava-activity-graph
       (if data
         [ui/appear-anon "opacity slow"
          [:div.flex
           [:div.strava-activity-graph-legend
            [:div.strava-activity-graph-legend-high
             data-max]
            [:div.strava-activity-graph-legend-current
             (map #(str (util/format-number % 2) " ") @cursor-pos)]
             ; (nth data (/ data-size ))]
            [:div.strava-activity-graph-legend-low
             data-min]
            [:div
             kind]]
           [:canvas
            {:ref #(when %
                     (reset! canvas %)
                     (draw-graph % kind data cursor-pos))
             :on-mouse-move #(reset! cursor-pos
                                     (xy-in-rect % [:x :y]
                                                 (.getBoundingClientRect (.-target %))))
             }]]]
         [views/loading-spinner true]) ])))

(defn activity-graphs
  [activity]
  (let [watts @(rf/subscribe [:strava/activity-stream (:id activity) "watts" 10])]
    [:div.strava-activity-graphs
     [graph-canvas "watts" activity]
     [graph-canvas "heartrate" activity]
     [graph-canvas "velocity_smooth" activity]
     [graph-canvas "cadence" activity]]))


(defn activity-dot
  [activity i num-total watts-high]
  (let [hovered? (r/atom false)
        opened? (r/atom false) ;like above but lock with click
        tab (r/atom #_:splits :summary)
        cutoff 80
        size (str (max 0.5 (* 1.5 (/ (:suffer_score activity) 300))) "em")]
    (fn [activity i num-total watts-high]
      [:<>
       [:div.strava-activity
       (if @opened?
         [:div.strava-activity-dot.strava-activity-dot-expanded ;.section-with-media-bg-wrapper
          {:on-click #(reset! opened? false)}

          [:div.strava-activity-top-bg]
          
          (let [details @(rf/subscribe [:strava/content [:activity (:id activity)]])
                tab-button (fn [id-key]
                             [:button {:style {:background (when (= id-key @tab)
                                                             "rgba(252, 76, 2, 0.3)")}
                                       :on-click #(do (.stopPropagation %)
                                                      (reset! tab id-key))}
                              (name id-key)])
                tabs (merge {:summary [activity-stats activity details]
                       :splits [activity-splits details]
                       :segments [activity-segments details]
                       :map [activity-map-leaflet activity]
                       :graphs [activity-graphs activity]}
                            (when (< 1 (count (:laps details)))
                              {:laps [activity-laps details]}))]
                       
             [:div.strava-activity-full
              {:ref #(when % ; fetch our additional stuff on mount
                       (rf/dispatch [:strava/fetch-activity (:id activity)])
                       (rf/dispatch [:strava/fetch-stream (:id activity)
                                     "latlng,watts,heartrate,velocity_smooth,altitude,cadence,time"]))}

              [:h3 [:b (:name activity)]]
              [:div.strava-activity-full-inner
               (@tab tabs)] 
              (into [:div.flex
                     {:style {:position :absolute
                              :top 0 :right 0}}]
                    (map (fn [k] [tab-button k]) (keys tabs))) ])]
         
         [:div.strava-activity-dot
          {:style {:left (str (* 100 (/ i num-total)) "%")
                   :bottom (str (* 100 (/ (- (:average_watts activity) cutoff)
                                          (- watts-high cutoff))) "%")
                   :width size :height size}
           :on-mouse-enter #(reset! hovered? true)
           :on-mouse-leave #(reset! hovered? false)
           :on-click #(do (swap! opened? not)
                          (reset! hovered? false))}])
       (when @hovered?
         [:div.strava-activity-summary
          [:span [:b (:name activity)]]
          [:span (:suffer_score activity) " relative effort "]
          [:span (:average_watts activity) " watts"]])]
       (when @opened?
         [activity-map-canvas activity])])))



(defn activities-graph "List multiple activities, currently as a graph from watts and RE"
  []
  (let [height "22em"
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
  (let [activity @(rf/subscribe [:strava/activity-stream (:id activity) "latlng" 5])
        [lats lngs] (map #(map % activity) [first second])
        [lat-max lat-min] (map #(apply % lats) [max min])
        [lng-max lng-min] (map #(apply % lngs) [max min]) ]
    [:div.strava-activity-map
     (if activity
       [ui/appear-anon "opacity extra-slow"
        (for [[lat lng] activity]
         [:span.strava-activity-map-point
          {:style {:bottom (str (* 100 (/ (- lat lat-min) (- lat-max lat-min))) "%")
                   :left (str (* 100 (/ (- lng lng-min) (- lng-max lng-min))) "%") }} ])]
       [views/loading-spinner true]) ]))


(defn activity-map-leaflet "Visualize latlng somehow! Currently goes behind activity-full but putting it on bg would be best (fix latlng so fits...)"
  [activity]
  (let [latlng @(rf/subscribe [:strava/activity-stream (:id activity) "latlng" 5])
        [lats lngs] (map #(map % latlng) [first second])
        [lat-max lat-min] (map #(apply % lats) [max min])
        [lng-max lng-min] (map #(apply % lngs) [max min]) ]
    [:div.strava-activity-map
     {:on-click #(.stopPropagation %)}
     (if latlng
       [ui/appear-anon "opacity slow"
        [:div
         [:> js/ReactLeaflet.MapContainer
          {:center [(/ (apply + lats) (count lats))
                    (/ (apply + lngs) (count lngs))]
           :zoom 11}

          [:> js/ReactLeaflet.TileLayer
           {:attribution "&copy; contributors"
            :url "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"}]
          [:> js/ReactLeaflet.Polyline
           {:pathOptions {:color "#fc4c02"}
            :positions latlng}]]]]
       [views/loading-spinner true]) ]))



(defn draw-map [canvas latlng watts]
  (let [ctx (.getContext canvas "2d")
        [lats lngs] (map #(map % latlng) [first second])
        [lat-max lat-min] (map #(apply % lats) [max min])
        [lng-max lng-min] (map #(apply % lngs) [max min])
        [watt-max watt-min] (map #(apply % watts) [max min])
        [w h] [(.-clientWidth canvas) (.-clientHeight canvas)]
        [start-lat start-lng] (first latlng)
        [end-lat end-lng] (last latlng)
        normalize (fn [lat lng]
                    [(* w (/ (- lat lat-min) (- lat-max lat-min)))
                     (- h (* h (/ (- lng lng-min) (- lng-max lng-min))))])
        line-to (fn [[x y]] (.lineTo ctx x y))
        move-to (fn [[x y]] (.moveTo ctx x y))]
    (set! (.-fillStyle ctx) "rgb(252,176,172)")
    (set! (.-lineWidth ctx) 3)
    (.beginPath ctx)
    (move-to (normalize start-lat start-lng))
    (doseq [[[lat lng] watt] (partition 2 (interleave latlng (or watts (repeat 200))))]
      (set! (.-strokeStyle ctx) (str "rgb("
                                     "252.7,"
                                     (* 255 (util/rescale-to-frac watt (or watt-min 0) (or watt-max 250))) ","
                                     "50" ")"))
      (line-to (normalize lat lng)))
    (.fillText ctx "start"
               (* w (/ (- start-lat lat-min) (- lat-max lat-min)))
               (* h (/ (- start-lng lng-min) (- lng-max lng-min))))
    (.stroke ctx)))


(defn activity-map-canvas "Visualize latlng somehow! Currently goes behind activity-full but putting it on bg would be best (fix latlng so fits...)"
  [activity]
  (let [latlng @(rf/subscribe [:strava/activity-stream (:id activity) "latlng" 10])
        [lats lngs] (map #(map % latlng) [first second])
        [lat-max lat-min] (map #(apply % lats) [max min])
        [lng-max lng-min] (map #(apply % lngs) [max min])
        watts @(rf/subscribe [:strava/activity-stream (:id activity) "watts" 10])
        node (r/atom nil)]
    [:div.strava-activity-map
     (if latlng
       [ui/appear-anon "opacity slow"
        [:canvas#strava-activity-map
         {:ref #(when %
                  (reset! node %)
                  (draw-map % latlng watts))} ]]
       [views/loading-spinner true]) ]))



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
     [ui/appear-anon "opacity"
      [:img.media-as-bg {:src "img/strava-heatmap-3.png"}]]
     [:h1  [:img {:src "img/strava_logo_nav.png"}]]
     (when (:error data)
       [:div
        [:h3 "Rate limited?"]
        [:p "Uh-oh, looks like we failed to fetch the strava data. Try refreshing the page."]])
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

