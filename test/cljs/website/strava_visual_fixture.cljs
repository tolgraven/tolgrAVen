(ns website.strava-visual-fixture
  "Native Strava owners with fixed read responses; never provider access or writes."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.macros :as m]
            [website.modules.strava.views :as strava]
            [website.modules.strava.schema :as schema]
            [website.validation :as validation]
            [website.components.ui.overlays :as overlays]
            ["leaflet" :as leaflet]))

(def activities
  [{:id 91001
    :name "Fixture morning ride"
    :type "Ride"
    :sport_type "Ride"
    :distance 42000
    :moving_time 5400
    :elapsed_time 5700
    :start_date "2026-10-14T08:00:00Z"
    :start_date_local "2026-10-14T10:00:00Z"
    :gear_id "b8866753"
    :average_speed 7.8
    :average_watts 175
    :average_heartrate 145
    :kilojoules 1500
    :pr_count 2
    :private false}
   {:id 91002
    :name "Fixture evening ride"
    :type "Ride"
    :sport_type "Ride"
    :distance 18000
    :moving_time 2700
    :elapsed_time 2900
    :start_date "2026-10-13T16:00:00Z"
    :start_date_local "2026-10-13T18:00:00Z"
    :gear_id "b8866753"
    :average_speed 6.7
    :average_watts 135
    :average_heartrate 130
    :kilojoules 900
    :pr_count 0
    :private false}])

(def totals
  {:all_ride_totals {:count 120, :distance 4800000, :moving_time 650000, :elevation_gain 45000}
   :recent_ride_totals {:count 8, :distance 240000, :moving_time 36000, :elevation_gain 1800}
   :ytd_ride_totals {:count 60, :distance 2200000, :moving_time 320000, :elevation_gain 17000}})

(def athlete
  {:firstname "Visual"
   :lastname "Cyclist"
   :bio "Fixed public activity data, rendered by the native Strava owner."
   :city "Stockholm"
   :weight 72
   :profile_medium "/img/tolgrav-square.png"})

(def details
  (into {} (map (fn [activity]
                  [(:id activity)
                   (assoc activity
                          :description "A local fixture ride.\nKeep the original activity layout and controls."
                          :total_elevation_gain 420
                          :kudos_count 1
                          :photos {:count 1, :primary {:urls {:600 "/img/tolgrav-square.png"}}}
                          :splits_metric [{:distance 1000, :average_speed 6.5, :elevation_difference 4}
                                          {:distance 1000, :average_speed 8.0, :elevation_difference -3}
                                          {:distance 1000, :average_speed 7.2, :elevation_difference 2}]
                          :laps [{:distance 21000, :average_speed 7.1, :average_watts 160}
                                 {:distance 21000, :average_speed 8.1, :average_watts 185}])]) activities)))

(def streams
  (mapv (fn [[kind values]] {:type kind, :data (vec values)})
        [["latlng" (for [index (range 150)]
                     [(+ 59.30 (* index 0.00008))
                      (+ 18.03 (* index 0.00006) (* 0.002 (js/Math.sin (/ index 12))))])]
         ["watts" (map #(+ 120 (mod (* % 7) 110)) (range 150))]
         ["heartrate" (map #(+ 125 (mod % 30)) (range 150))]
         ["velocity_smooth" (map #(+ 6 (/ (mod % 24) 10)) (range 150))]
         ["cadence" (map #(+ 70 (mod % 25)) (range 150))]]))

(defn- response [uri path]
  (cond
    (= uri "/api/integrations/settings") {:strava {:athlete_id 91000}}
    (= uri "/api/integrations/intervals")
    (mapv (fn [index] {:date (str "2026-10-" (- 14 (* index 3)))
                       :count (+ 4 index)
                       :distance (+ 120000 (* index 10000))
                       :moving_time 20000
                       :calories 3200
                       :total_elevation_gain 900
                       :training_load 250
                       :fitness 44
                       :fatigue 48}) (range 3))
    (= uri "/api/integrations/strava")
    (cond
      (= path "athletes/91000/stats") totals
      (= path "athlete") athlete
      (= path "athlete/activities") activities
      (= path "segments/starred") []
      (= path "gear/b8866753") {:name "Fixture bike - Carbon road bike", :converted_distance 2400}
      :else (when-let [[_ id suffix] (re-matches #"activities/(91001|91002)(.*)" path)]
              (cond
                (string/starts-with? suffix "/streams") streams
                (= suffix "/kudos") [{:firstname "Native", :lastname "Supporter"}]
                (string/starts-with? suffix "?include_all_efforts=") (get details (js/parseInt id 10))
                :else nil)))
    :else nil))

(defn install-transport! []
  (when (validation/explain schema/content {:activities activities
                                           :activity details
                                           :athlete athlete
                                           :stats totals
                                           :activity-stream {91001 streams, 91002 streams}})
    (throw (js/Error. "Invalid native Strava fixture data")))
  ;; The native init, fetch, result events and subscriptions remain unchanged.
  ;; Only the existing HTTP transport effect is scoped to these disposable cases.
  (r/reg-fx :http-xhrio
    (fn [{:keys [method uri url-params on-success]}]
      (when-not (= :get method)
        (throw (js/Error. "Strava visual fixtures reject provider writes")))
      (if-let [value (response uri (:path url-params))]
        (r/dispatch (conj on-success value))
        (throw (js/Error. (str "Unexpected Strava fixture read: " uri)))))))

(defonce *tile-transport (atom nil))

(defn ^:export local-tiles-ready? [] (true? (:installed? @*tile-transport)))
(defn ^:export local-tile-reads [] (:reads @*tile-transport 0))

(defn- install-tile-transport! []
  (let [prototype (.-prototype (.-TileLayer leaflet))
        original (.-getTileUrl prototype)
        cleanup! (fn []
                   (set! (.-getTileUrl prototype) original)
                   (reset! *tile-transport nil))
        read! (fn [coordinates]
                (this-as layer
                  (if (= "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" (.-_url layer))
                    (do
                      (when-not (some-> (.-_map layer) (.getContainer) (.closest "#strava"))
                        (throw (js/Error. "Map tile escaped its native Strava owner")))
                      (when-not (and (number? (.-x coordinates)) (number? (.-y coordinates))
                                     (number? (.-z coordinates)))
                        (throw (js/Error. "Native Leaflet tile coordinates are missing")))
                      (swap! *tile-transport update :reads inc)
                      ;; A pinned local cartographic image is transport data. The
                      ;; real map, TileLayer, projection and polyline stay native.
                      "/img/strava-heatmap-new-320w.webp")
                    (.call original layer coordinates))))]
    (reset! *tile-transport {:installed? true, :reads 0})
    (set! (.-getTileUrl prototype) read!)
    (.addEventListener js/window "pagehide" cleanup! #js {:once true})
    (fn []
      (.removeEventListener js/window "pagehide" cleanup!)
      (cleanup!))))

(m/defpage <page> []
  (r/use-layout-effect install-tile-transport! [])
  [strava/<strava>])

(defn <fixture> []
  [:<>
   [overlays/<zoom-to-modal> :fullscreen]
   [:main#main.main-content.perspective-top [<page>]]])
