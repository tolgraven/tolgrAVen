(ns tolgraven.strava.module
  (:require
    [tolgraven.strava.events]
    [tolgraven.strava.subs]
    [tolgraven.strava.views :as view]))

(def spec
  {:id :strava
   :view {:view #'view/strava}})
