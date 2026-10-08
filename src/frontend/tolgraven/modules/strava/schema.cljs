(ns tolgraven.modules.strava.schema
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:activity-expanded [:maybe :int]}))

(def activity
  (c/optional-map {:id [:or :int :string] :name :string :type :string :sport_type :string
                   :distance number? :moving_time number? :elapsed_time number?
                   :start_date :string :start_date_local :string :gear_id [:maybe :string]
                   :total_elevation_gain number? :average_speed number?
                   :map (c/optional-map {:summary_polyline [:maybe :string] :polyline [:maybe :string]})}))
(def content
  (c/optional-map {:activities [:sequential activity] :activity [:map-of c/id activity]
                   :athlete :map :stats :map :starred [:sequential :map] :gear [:map-of c/id :map]
                   :activity-stream [:map-of c/id [:or :map [:sequential :map]]]
                   :segment-stream [:map-of c/id [:or :map [:sequential :map]]]
                   :kudos [:map-of c/id [:sequential :map]] :error [:sequential :any]}))
