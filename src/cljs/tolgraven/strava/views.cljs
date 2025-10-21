(ns tolgraven.strava.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [reanimated.core :as anim]
   [tolgraven.ui :as ui]
   [tolgraven.views-common :as views]
   [tolgraven.util :as util :refer [at]]
   [react-leaflet]))

(declare activity-map)
(declare activity-map-canvas)
(declare activity-map-leaflet)

(defn activity-photo "Display photo for activity and allow to view fullscreen. Sadly seems can only get primary photo from Strava, not all?"
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

(defn activity-split "Single split dot"
  [i {:keys [average_speed] :as split} num-splits min-speed max-speed space-per-split]
  (let [hovered? (r/atom false)
        left (* space-per-split num-splits (/ i num-splits))
        bottom (+ 10 (* 85 (util/rescale-to-frac average_speed min-speed max-speed)))
        gradient (* 100 (/ (:elevation_difference split)
                           (:distance split)))]
    (fn [i {:keys [average_speed] :as split} num-splits min-speed max-speed space-per-split]
      [:<>
       [:div.strava-activity-split.strava-activity-dot
        {:style {:left (str left "%")
                 :bottom (str bottom "%")}
         :on-mouse-enter #(reset! hovered? true)
         :on-mouse-leave #(reset! hovered? false)}
        [:span (util/format-number (* 3.6 (:average_speed split)) 1)]]
       [:div.strava-activity-split-gradient
         {:style {:position "absolute"
                  :width "2.5rem"
                  :height "2px"
                  :background "var(--dark-6)"
                  :left (str left "%")
                  :bottom "2rem"
                  :transform (str "rotate(" (* -10 gradient) "deg)")}}]
       [:p.strava-activity-split-legend
        {:class (when @hovered? "strava-activity-dot")
         :style {:left (str (+ 2 left) "%") ;ugly magic number but aligns perfectly
                 :bottom "0.66rem"}
         :on-mouse-enter #(reset! hovered? true)
         :on-mouse-leave #(reset! hovered? false)}
        i]
       (when @hovered?
         [:div.strava-activity-split-details.strava-popup.strava-stats
           {:style {:position :absolute ;TODO proper tooltip positioning. but put that in ui haha
                    :left (str (+ left 9) "%")
                    :bottom (str (- bottom 25) "%")}}
           [:div.flex
            [:div
             [:p "Heartrate"]
             [:p "Grade"]]
           [:div
            [:p (util/format-number (:average_heartrate split) 0)
             [:span " bpm"]]
            [:p (util/format-number gradient 2)
             [:span "%"]]]]])])))

(defn activity-splits "km splits from activity"
  [{:keys [splits_metric] :as details}]
  (let [num-splits (count splits_metric)
        [min-speed max-speed] (map #(apply % (map :average_speed splits_metric)) [min max])
        space-per-split 6
        size (* space-per-split num-splits)]
    [:div.strava-activity-splits.flex
     (map-indexed
      (fn [i {:keys [average_speed] :as split}]
        (with-meta
         [activity-split i split num-splits min-speed max-speed space-per-split]
         {:key (str "strava-activity-split-" i)}))
      splits_metric)

     (when (< 100 size) ;overflow, scroll sideways
       [:span.scroll-reminder "scroll " [:i.fa.fa-chevron-right]]) ]))

(defn activity-segment "List a specific segment"
  [segment]
  (let [hovered? (r/atom false)]
    (fn [segment]
      [:<>
       [:div.strava-activity-segment.flex
        [:p
         {:on-mouse-over #(do (rf/dispatch [:strava/fetch-segment-stream
                                            (-> segment :segment :id)
                                            "latlng,altitude"])
                              (reset! hovered? true))
          :on-mouse-leave #(reset! hovered? false)}
         (:name segment) ]
        [:p
         (when-let [achievements (:achievements segment)]
           (for [achievement achievements] ^{:key (str "strava-segment-achievement-" (:type achievement) "-" (:rank achievement))}
             [:i.fa.fa-award.strava-award
              {:class (case (:type achievement)
                        "pr" (case (:rank achievement) ; TODO add for KOM/top10rank
                               1 "gold"
                               2 "silver"
                               3 "bronze")
                        "segment_effort_count_leader" "blue"
                        "overall" "purple")}]))
         (util/format-number (* 3.6 (/ (:distance segment)
                                       (:elapsed_time segment))) 1)
         [:span " km/h"]] ]
       (when @hovered?
         [:div.strava-popup
          [:pre @(rf/subscribe [:strava/content [:segment-stream (-> segment :segment :id)]])]
          "Map etc goes here"])])))

(defn activity-segments "Segments for activity"
  [{:keys [segment_efforts] :as details}]
  [:div.strava-activity-segments
   (for [segment segment_efforts] ^{:key (str "strava-activity-segment-" (:name segment))}
     [activity-segment segment])])

(defn activity-laps "Laps for activity"
  [{:keys [laps] :as details}]
  [:div.strava-activity-laps
   (for [lap laps] ^{:key (str "strava-lap-" (:name lap))}
     [:div.strava-activity-lap
      [:h4 (:name lap)]
      [:p (util/format-number (/ (:distance lap) 1000) 1) [:span " km"]]
      [:p (util/format-number (* 3.6 (:average_speed lap)) 1) [:span " km/h"]]
      [:p (util/format-number (:average_watts lap) 1) [:span " watts"]]])])

(defn kudo "Strava icon for kudos, show name on hover"
  [kudoer]
  (let [hovered? (r/atom false)]
    (fn [kudoer]
      [:span.strava-kudo-dot-container
       {:style {:position :relative}
        :on-mouse-enter #(reset! hovered? true)
        :on-mouse-leave #(reset! hovered? false)}
       [:img.strava-kudo-dot
        {:src "img/strava-icon.png" }]
       (when @hovered?
         [:div.strava-kudos-popup.strava-popup
           [:span (:firstname kudoer) " " (:lastname kudoer)]])])))

(defn kudos "List kudos"
  [activity]
  (let [kudoers @(rf/subscribe [:strava/content [:kudos (:id activity)]])]
    [:div.strava-activity-kudos
     {:style {:position :relative}
      :ref #(when %
              (rf/dispatch [:strava/fetch-kudos (:id activity)]))}
     (if kudoers
       (for [kudoer kudoers] ^{:key (str "strava-kudoer-" (:firstname kudoer) "-" (:lastname kudoer))}
         [ui/seen-anon "slide-in slow"
          [kudo kudoer]])
       [ui/loading-spinner true nil
        {:style {:font-size "70%"
                 :width "0.7em"
                 :height "1.0em"}}]) ]))

(defn gear "Display gear details"
  [id]
  (let [hovered? (r/atom false)
        gear (rf/subscribe [:strava/content [:gear id]])]
    (fn [id]
      [:div.strava-activity-gear
       {:on-mouse-enter #(reset! hovered? true)
        :on-mouse-leave #(reset! hovered? false)}
       (let [[nickname desc] (string/split (get-in @gear [:name])
                                           #" - ")]
         [:<> [:span nickname] [:p desc]])
       (when @hovered?
         (let [info @(rf/subscribe [:strava/content [:gear-info id]])]
           [:div.strava-activity-gear-popup.strava-popup
            {:style {:animation "fade-in 1.5s ease 0.3s forwards"}}
            
            [:div
             {:style {:background-image (str "url(" (:img info) ")")}}
             [:p [:span (:converted_distance @gear)] [:span "km"]]
             [:p (:desc info)]]]))])))

(defn activity-stats
  [activity details]
  [:<>
   [:div.flex
    {:style {:justify-content :space-between
             :font-size "90%"}}
    (into [:div.strava-activity-description]
          (map #(vec [:p %]) (string/split-lines (:description details))))]
   [:div.strava-activity-stats.flex
   
   [:div.flex
    [ui/appear-anon "opacity extra-slow"
     [:div.strava-activity-stats-descriptions
      (when (:kilojoules activity)
        [:p "Kilojoules"])
      [:p "Watts"]
      [:p "Average speed"]
      [:p "Distance"]
      [:p "Elevation gain"]
      (when (:average_heartrate activity)
        [:p "Heartrate"])
      (when (pos? (:pr_count activity))
        [:p "PRs"])
       (when (pos? (:kudos_count details))
        [:p "Kudos"])
       [:p "Bike"]]]
    [:div.strava-activity-stats-numbers
     (when (:kilojoules activity)
        [:p (:kilojoules activity)])
     [:div [:p (:average_watts activity)]]
     [:p (util/format-number (* 3.6 (:average_speed activity)) 1) [:span " km/h"]]
     [:p (util/format-number (/ (:distance activity) 1000) 1) [:span " km"]]
     [:p (:total_elevation_gain details) [:span " m"]]
     (when-let [hr (:average_heartrate activity)]
       [:p hr [:span " bpm"]])
     (when (pos? (:pr_count activity))
       [:p [:i.fa.fa-award.strava-award] (:pr_count activity)])
     (when (pos? (:kudos_count details))
       [kudos activity])
     [gear (:gear_id activity)]]]

   [activity-photo (:photos details)] ] ])


; some ideas:
; diff colors for diff heights like intervals watts zones (use color lib to mod)
; option to put dot at each point for graphs with fewer points
; zoom - controlling by what data is provided makes most sense I think
; but need support for displaying while selecting range to zoom into...
; could be a general "highlight area" tho...
(defn draw-graph
  [canvas id data cursor-pos & {:keys [colors zoom current-drag]
                                :or {colors {:line "rgb(252,136,54)"
                                             :fill "rgb(252,176,172)"
                                             :highlight "rgb(52,136,254)"}
                                     zoom [0.0 1.0]}}]
  (util/resize-canvas-to-display-size canvas)
  (let [ctx (.getContext canvas "2d")
        [data-max data-min] (map #(apply % data) [max min])
        [w h] [(.-width canvas) (.-height canvas)]
        size (count data)
        cursor-index (int (* size (/ (first @cursor-pos) w)))]
    (.clearRect ctx 0 0 w h)
    (set! (.-strokeStyle ctx) (:line colors))
    (set! (.-fillStyle ctx) (:fill colors))
    (set! (.-lineWidth ctx) 1.5)
    (set! (.-shadowBlur ctx) 4)
    (set! (.-shadowOffsetX ctx) 1)
    (set! (.-shadowOffsetY ctx) 1)
    (set! (.-shadowColor ctx) (:highlight colors));
    (.beginPath ctx)
    (.moveTo ctx 0 (- h (* h (util/rescale-to-frac (first data) data-min data-max))))
    
    (doall (map-indexed
     (fn [i point]
      (let [x (* w (util/rescale-to-frac i 0 size))
            y (- h (* h (util/rescale-to-frac point data-min data-max)))]
        (.lineTo ctx x y)
        (when (= i cursor-index)
          (set! (.-strokeStyle ctx) (:highlight colors))
          (set! (.-lineWidth ctx) 2.5)
          (.arc ctx x y, 4, 0, 2 * js/Math.PI)
          (.stroke ctx)
          (set! (.-lineWidth ctx) 2.5)
          (.moveTo ctx x y)
          ; (.stroke ctx)
          (set! (.-strokeStyle ctx) (:line colors))
          (set! (.-lineWidth ctx) 1.5))))
     data))
    (.stroke ctx)))

(defn graph-canvas "Canvas for drawing graphs, and legend"
  [label unit data] ;also maybe an atom we can update to change downsampling/range
  (let [[data-max data-min] (map #(util/format-number % 1)
                                 (map #(apply % data) [max min]))
        data-size (count data)
        cursor-pos (r/atom [0 0])
        canvas (r/atom nil)
        on-move #(reset! cursor-pos (util/xy-in % [:x :y]))
        zoom-to (r/atom {:start 0.0 :end 1.0})
        on-down #(swap! zoom-to update :start util/xy-in % [:x])
        on-up   #(swap! zoom-to update :end util/xy-in % [:x])]
    (fn [label unit data]
      [:div.strava-activity-graph
       (when data
         [ui/appear-anon "zoom-y slow"
          [:div.strava-activity-graph-inner
           [:canvas
            {:ref #(when %
                     (reset! canvas %)
                     (draw-graph % label data cursor-pos))
             :on-mouse-move on-move
             :on-mouse-down on-down
             :on-mouse-up   on-up
             :on-touch-start on-move
             :on-touch-move on-move}]
           
           [:div.strava-activity-graph-legend.flex
            [:div
             label]
            [:div (pr-str @zoom-to)]
            [:div.strava-activity-graph-legend-current
             (let [width (if @canvas
                           (-> (.getContext @canvas "2d")
                               .-canvas
                               .-width)
                           (first @cursor-pos))
                   current (-> (nth data (-> (first @cursor-pos)
                                             (/ width)
                                             (* data-size)
                                             int
                                             (max 0)))
                               (util/format-number 1))]
               [:b current [:span " " unit]])]
            [:div.strava-activity-graph-legend-range
             [:span data-min] " - " [:span data-max]]]]])]))) ; soo, for spinner would need to track whether not yet data or doesnt exist...

(defn activity-graphs
  [activity]
  (let [watts @(rf/subscribe [:strava/activity-stream (:id activity) "watts" 10])]
    (into [:div.strava-activity-graphs]
          (map (fn [[kind unit]]
                 [graph-canvas
                  (string/capitalize (name kind)) 
                  unit
                  @(rf/subscribe [:strava/activity-stream (:id activity) (name kind) 25])])
               [[:watts "W"] [:heartrate "bpm"] [:velocity_smooth "km/h"] [:cadence "rpm"]])))) ; TODO expose whats available and have a button with dropdown to add graph, like shitty v of intervals.icu


(defn activity-dot
  [activity i num-total watts-high]
  (let [hovered? (r/atom false)
        which-expanded (rf/subscribe [:strava/activity-expanded]) ;needs be post/sub so can have prev/next btns
        opened? (fn [] ((fnil = -1) @which-expanded i))
        tab (r/atom #_:graphs :summary)
        cutoff 80
        size (max 0.85 (/ (:kilojoules activity) 1000))
        anim-size (r/atom (rand 0.2))]
    (fn [activity i num-total watts-high]
      (when-not (:private activity)
       [:<>
       [:div.strava-activity
       (if (opened?)
         [:div.strava-activity-dot.strava-activity-dot-expanded ;.section-with-media-bg-wrapper
          {:on-click #(.stopPropagation %)}

          [:div.strava-activity-top-bg]
          
          (let [details @(rf/subscribe [:strava/content [:activity (:id activity)]])
                tab-button (fn [id-key]
                             [:button.strava-tab-btn
                              {:class (when (= id-key @tab)
                                        "active-tab")
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
              (into [:div.strava-activity-tabs.flex ;tab buttons
                     {:style {:position :absolute
                              :bottom 0 :right 0}}]
                    (map (fn [k] [tab-button k])
                         (keys tabs))) 
              [ui/close #(do (rf/dispatch [:strava/activity-expand nil])
                             (rf/dispatch [:strava/state [:stats-minimized] false]))]])]
 
         [:div.strava-activity-dot
          {:style {:left (str (* 100 (/ i num-total)) "%")
                   :bottom (str (* 100 (/ (- (:average_watts activity) cutoff)
                                          (- watts-high cutoff))) "%")
                   :width (str @anim-size "em") :height (str @anim-size "em")}
           :on-mouse-enter #(reset! hovered? true)
           :on-mouse-leave #(reset! hovered? false)
           :on-click #(do (.stopPropagation %)
                          (rf/dispatch [:strava/activity-expand i])
                          (rf/dispatch [:strava/state [:stats-minimized] true])
                          (reset! hovered? false))}
          [anim/timeout #(reset! anim-size size) (+ 750 (rand-int 2500))]])
       (when @hovered?
         [:div.strava-activity-summary
          [:span [:b (:name activity)]]
          [:span (:kilojoules activity) " kilojoules "]
          [:span (:average_watts activity) " watts"]])]
       (when (opened?)
         [activity-map-canvas activity])]))))


(defn activities-graph "List multiple activities, currently as a graph from watts and RE"
  []
  (let [num-activities 30
        activities @(rf/subscribe [:strava/content [:activities]])
        watts-high (apply max (map :average_watts activities))]
    [ui/seen-anon "opacity extra-slow"
     [:div.strava-activities
      {:style {:position :relative}}
      (map-indexed 
       (fn [i activity] ^{:key (str "strava-activity-dot-" i)}
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
       [ui/loading-spinner true]) ]))


(defn activity-map-leaflet "Visualize latlng somehow! Currently goes behind activity-full but putting it on bg would be best (fix latlng so fits...)"
  [activity]
  (let [latlng @(rf/subscribe [:strava/activity-stream (:id activity) "latlng" 15])
        [lats lngs] (map #(map % latlng) [first second])]
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
       [ui/loading-spinner true]) ]))



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
  (let [latlng @(rf/subscribe [:strava/activity-stream (:id activity) "latlng" 50])
        [lats lngs] (map #(map % latlng) [first second])
        watts @(rf/subscribe [:strava/activity-stream (:id activity) "watts" 50])
        node (r/atom nil)]
    [:div.strava-activity-map
     (if latlng
       [ui/appear-anon "opacity slow"
        [:canvas#strava-activity-map
         {:ref #(when %
                  (reset! node %)
                  (draw-map % latlng watts))} ]]
       [ui/loading-spinner true]) ]))


(defn strava-totals-stats
  [athlete category heading]
  (let [stats (category athlete)
        distance (-> (:distance stats)
                     (/ 1000)
                     (util/format-number 2))
        hours (/ (:moving_time stats) 3600)]
    [:div
     [:h3 heading]
     [:div (:count stats)]
     [:div distance                                               [:span " km"]]
     [:div (util/format-number hours 1)                           [:span " hours"]]
     [:div (util/format-number (/ distance (:count stats)) 2)     [:span " km"]]
     [:div (util/format-number (/ distance hours) 1)              [:span " km/h"]]
     [:div (util/format-number (/ (:elevation_gain stats) 1000))  [:span " km"]]]))


(defn strava-general-stats "Box of stats (rides, total distance and stuff)"
  []
  (let [stats @(rf/subscribe [:strava/content [:stats]])]
    [:<>
     [:div.strava-stats-legend
      [:h3 [:img {:src "img/strava-icon.png"
                  :style {:width "1.25em"}}]]
      [:div "Rides"]
      [:div "Total distance"]
      [:div "Time"]
      [:div "Average distance"]
      [:div "Average speed"]
      [:div "Elevation gain"]]
     [strava-totals-stats stats :all_ride_totals "Total"] 
     [ui/carousel-normal "strava-general-stats-carousel"
      {}
      [[strava-totals-stats stats :recent_ride_totals "Recent"]
       [strava-totals-stats stats :ytd_ride_totals (.getFullYear (js/Date.))]]]]))

(defn strava-details-stats "Use rides to calc stuff"
  []
  [:div])


(defn intervals-totals-stats
  [stats index]
  (let [{:keys [count calories total_elevation_gain
                training_load fitness fatigue] :as stats} (get stats index)
        distance (-> (:distance stats)
                     (/ 1000)
                     (util/format-number 2))
        hours (/ (:moving_time stats) 3600)]
    [:div
     [:h3 (-> stats :date (string/replace-first #"\d*-" ""))]
     [:div count]
     [:div distance                           [:span " km"]]
     [:div (util/format-number hours 1)       [:span " hours"]]
     [:div calories                           [:span " kcal"]]
     [:div total_elevation_gain               [:span " m"]]
     [:div training_load                      [:span " TSS"]]
     [:div (util/format-number fitness 1)     [:span " CTL"]]
     [:div (util/format-number fatigue 1)     [:span " ATL"]]]))

(defn intervals-general-stats "Box of stats (graphs n shit!)"
  []
  (let [stats @(rf/subscribe [:intervals/content [:summary]])]
    [:<> ;div.strava-stats-intervals
     [:div.strava-stats-legend
      [:h3 [:img {:src "img/intervals-icon.png"
                  :style {:width "1.25em"
                          :border-radius "50%"}}]
      " Week"]
      [:div "Rides"]
      [:div "Distance"]
      [:div "Time"]
      [:div "Calories"]
      [:div "Elevation"]
      [:div "Load"]
      [:div "Fitness"]
      [:div "Fatigue"]]
     [intervals-totals-stats stats 0 "This"]
     [intervals-totals-stats stats 1 "Last"]
     [intervals-totals-stats stats 2 "Before"]]))

(defn intervals-graphs "Nice graphs"
  []
  (let [stats @(rf/subscribe [:intervals/content [:summary]])]
   [:div]))

(defn general-stats "Box of stats, from strava or other related provider"
  [stats]
  (let [active-tab (r/atom :strava-stats)
        strava-logo {:src "img/strava-icon.png"}
        intervals-logo {:src "img/intervals-icon.png"
                        :style {:border-radius "50%"}}]
    (fn [stats]
      [:div.strava-stats.flex
       {:class (when @(rf/subscribe [:strava/state [:stats-minimized]])
                 "stats-minimized")}

       [:div.strava-stats-tabs
        (for [tab [{:id :strava-stats       :caption "summary"  :logo strava-logo}
                   {:id :strava-detailed    :caption "detailed" :logo strava-logo}
                   {:id :intervals-stats    :caption "stats"    :logo intervals-logo}
                   {:id :intervals-graphs   :caption "graphs"   :logo intervals-logo}]]
          ^{:key (str "strava-general-stats-" (:id tab))}
          [:button.strava-tab-btn
           {:on-click #(reset! active-tab (:id tab))
            :class (when (= @active-tab (:id tab))
                     "active-tab")}
           [:img.strava-stats-tab-img (:logo tab)]
           [:div (:caption tab)]])]

       (case @active-tab
         :strava-stats      [strava-general-stats]
         :strava-detailed   [strava-details-stats]
         :intervals-stats   [intervals-general-stats]
         :intervals-graphs  [intervals-graphs])])))

(defn strava "Make an increasingly fancy visualizer feed thingy. Relies on [:content :strava] in db"
  []
  (let [data @(rf/subscribe [:strava/content])
        stats (:stats data)
        athlete (:athlete data)
        arrow (fn [direction]
                [:i.fa {:class (str "fa-arrow-" (name direction))
                        :style {:color "#fc4c02"}}])]
    [:section#strava.strava.section-with-media-bg-wrapper.covering-2
     {:on-click #(rf/dispatch [:strava/activity-expand nil])}
     [ui/appear-anon "opacity"
      [:img.media-as-bg {:src (:background data)}]]
     [ui/inset "Click the dots for details" 4]
     [:a {:href (:profile-url data)}
      [:h1  [:img {:style {:height "2rem"}
                   :src "img/strava_logo_nav.png"}]]]
     #_(when (:error data)
       [:div
        [:h3 "Rate limited?"]
        [:p "Uh-oh, looks like we failed to fetch the strava data. Try refreshing the page."]])
     (if athlete
       [ui/appear-anon "opacity"
        [:div.strava-profile.flex
         [ui/user-avatar {:avatar (:profile_medium athlete)
                          :name (str (:firstname athlete) " " (:lastname athlete))}
                         "strava-profile-image"]
        [:div.strava-athlete ;.flex
         [:h3 (:firstname athlete) " " (:lastname athlete)]
         [:div (:bio athlete)]
         [:div (:city athlete)]
         [:div (:weight athlete) " kg"]]
        [:div.strava-story (:story data)]]]
       [ui/loading-spinner (rf/subscribe [:loading :strava])])
      
     (if stats
       [general-stats stats]
       [ui/loading-spinner true :massive])

     [activities-graph] ]))

