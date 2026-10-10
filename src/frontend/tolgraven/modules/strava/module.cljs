(ns tolgraven.modules.strava.module
  {:bundle/depends-on #{:main :maps :carousel}}
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.modules.strava.events]
    [tolgraven.modules.strava.subs]
    [tolgraven.modules.strava.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :strava [])
   :id :strava
   :styles ["/css/tolgraven/modules/strava.min.css"]
   :view {:view #'view/<strava>}
   :init #(rf/dispatch [:strava/init])})
