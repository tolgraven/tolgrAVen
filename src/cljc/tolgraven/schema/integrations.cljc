(ns tolgraven.schema.integrations
  "Fields consumed by the UI, retaining provider extension fields. These are
   response contracts, not copies of complete third-party APIs."
  (:require [tolgraven.schema.common :as c]))
(def author (c/optional-map {:name [:maybe :string] :date [:maybe :string] :email [:maybe :string]}))
(def commit
  (c/optional-map {:sha :string :html_url :string :url :string
                   :commit (c/optional-map {:message :string :author author :committer author})
                   :files [:sequential (c/optional-map {:filename :string :status :string :patch :string
                                                       :additions c/nonnegative :deletions c/nonnegative})]}))
(def github
  (c/optional-map {:commits [:sequential commit] :repo [:sequential commit] :commit [:map-of :string commit]
                   :repo-headers :map :errors :any}))
(def activity
  (c/optional-map {:id [:or :int :string] :name :string :type :string :sport_type :string
                   :distance number? :moving_time number? :elapsed_time number?
                   :start_date :string :start_date_local :string :gear_id [:maybe :string]
                   :total_elevation_gain number? :average_speed number?
                   :map (c/optional-map {:summary_polyline [:maybe :string] :polyline [:maybe :string]})}))
(def strava
  (c/optional-map {:activities [:sequential activity] :activity [:map-of c/id activity]
                   :athlete :map :stats :map :starred [:sequential :map] :gear [:map-of c/id :map]
                   :activity-stream [:map-of c/id [:or :map [:sequential :map]]]
                   :segment-stream [:map-of c/id [:or :map [:sequential :map]]]
                   :kudos [:map-of c/id [:sequential :map]] :error [:sequential :any]}))
(def instagram-post
  (c/optional-map {:id :string :caption :string :media_type :string :media_url :string
                   :permalink :string :timestamp :string :thumbnail_url :string}))
(def search-result
  (c/optional-map {:found c/nonnegative :out_of c/nonnegative :page c/positive :search_time_ms c/milliseconds
                   :hits [:sequential [:map [:document :map]
                                      [:highlights {:optional true} [:sequential :map]]]]}))
(def search
  (c/optional-map {:query [:map-of c/named [:maybe :string]] :previous-query [:map-of c/named [:maybe :string]]
                   :results [:map-of c/named [:map-of :string search-result]]}))
