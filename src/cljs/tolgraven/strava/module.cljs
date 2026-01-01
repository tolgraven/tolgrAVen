(ns tolgraven.strava.module
  (:require
    [tolgraven.strava.events]
    [tolgraven.strava.subs]
    [tolgraven.strava.views :as view]))

(def spec
  {:id :strava
   :css ["https://unpkg.com/leaflet@1.7.1/dist/leaflet.css"]
   :view {:view #'view/strava}})
