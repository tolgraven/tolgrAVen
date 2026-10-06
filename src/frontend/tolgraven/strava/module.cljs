(ns tolgraven.strava.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.strava.events]
    [tolgraven.strava.subs]
    [tolgraven.strava.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :strava [])
   :id :strava
   :assets {:css ["https://unpkg.com/leaflet@1.7.1/dist/leaflet.css"]}
   :view {:view #'view/<strava>}
   :init #(rf/dispatch [:strava/init])})
