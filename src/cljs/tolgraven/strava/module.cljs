(ns tolgraven.strava.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.strava.events]
    [tolgraven.strava.subs]
    [tolgraven.strava.views :as view]))

(def spec
  {:content (get content-contract/module-content :strava [])
   :id :strava
   :assets {:css ["https://unpkg.com/leaflet@1.7.1/dist/leaflet.css"]}
   :view {:view #'view/strava}
   :init #(do (js/console.error "STRAVA YO")
              (rf/dispatch [:strava/init]))})
