(ns tolgraven.strava.subs
  (:require
    [re-frame.core :as rf]
    [tolgraven.util :as util]))


(rf/reg-sub :strava/content
 :<- [:content [:strava]]
 (fn [strava [_ path]]
   (get-in strava path)))

; XXX only currently works with latlng!!
(rf/reg-sub :strava/activity-stream ; get an activity stream, also downsample it
            ; but we should actually request a resolution so becomes common, like 100 points.
 :<- [:strava/content [:activity-stream]]
  (fn [activities [_ id stream-type downsampling]]
    (let [stream-type (or stream-type "latlng")
          downsampling (or downsampling 5)
          {:keys [type data] :as activity}
            (-> activities
                (get id)
                ((partial filter #(= (:type %) stream-type)))
                first)
          data (partition downsampling data)
          reducing #(reduce % [] data)]
      (case type
        "latlng" (reducing (fn [l v]
                             (conj l (map #(/ % downsampling)
                                          (apply map + v)))))
        #_"watts" (reducing (fn [l v]
                            (conj l (/ (apply + v) downsampling))))
        ))))

