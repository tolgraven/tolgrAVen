(ns tolgraven.strava.subs
  (:require
    [re-frame.core :as rf]))


(rf/reg-sub :strava/content
 :<- [:content [:strava]]
 (fn [strava [_ path]]
   (get-in strava path)))

; XXX only currently works with latlng!!
(rf/reg-sub :strava/activity-stream ; get an activity stream, also downsample it lol
 :<- [:strava/content [:activity-stream]]
  (fn [activities [_ id downsampling]]
    (let [downsampling (or downsampling 5)
          activity (-> activities
                       (get id)
                       first)]
      (when (= (:type activity) "latlng")
        (->> (partition downsampling (:data activity))
             (reduce (fn [m v]
                       (conj m (map #(/ % downsampling)
                                    (apply map + v))))
                     []))))))

