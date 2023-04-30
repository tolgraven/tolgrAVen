(ns tolgraven.strava.subs
  (:require
    [re-frame.core :as rf]
    [cljs-time.core :as ct]
    [cljs-time.format :as ctf]
    [cljs-time.coerce :as ctc]
    [tolgraven.util :as util]))


(rf/reg-sub :strava/content
 :<- [:content [:strava]]
 (fn [strava [_ path]]
   (get-in strava path)))

(rf/reg-sub :strava/state
 :<- [:state [:strava]]
 (fn [strava [_ path]]
   (get-in strava path)))

(rf/reg-sub :strava/activity-expanded
 :<- [:strava/state [:activity-expanded]]
 (fn [id [_ _]]
   (or id -1)))


; XXX only currently works with latlng!!
(rf/reg-sub :strava/activity-stream ; get an activity stream, also downsample it
            ; but we should actually request a resolution so becomes common, like 100 points.
 :<- [:strava/content [:activity-stream]]
  (fn [activities [_ id stream-type downsampling subrange]] ;subrange like [0.25 0.33] for a partial view...
    (let [stream-type (or stream-type "latlng")
          downsampling (or downsampling 5)
          {:keys [type data] :as activity}
            (-> activities
                (get id)
                ((partial filter #(= (:type %) stream-type)))
                first)
          data (partition downsampling data)
          reducing #(when (seq data) (reduce % [] data))]
      (case type
        "latlng" (reducing (fn [l v]
                             (conj l (map #(/ % downsampling)
                                          (apply map + v)))))
        "velocity_smooth" (reducing
                           (fn [l v]
                             (conj l (-> (apply + v)
                                         (/ downsampling)
                                         (* 3.6)))))
        (reducing (fn [l v]
                    (conj l (/ (apply + v) downsampling))))))))

; (ct/week-number-of-year @(rf/subscribe [:now-ct]))
(rf/reg-sub :strava/activities-for
  :<- [:strava/content [:activities]]
  :<- [:now-ct]
  (fn [[activities now] [_ period-type period-num]]
    (let [])
    ;get current time
    ; set cutoff time for :month / :week etc...
    ; filter
    ))

(rf/reg-sub :intervals/content
 :<- [:content [:intervals]]
 (fn [intervals [_ path]]
   (get-in intervals path)))

