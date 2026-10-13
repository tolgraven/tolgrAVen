(ns tolgraven.modules.strava.module
  {:bundle/depends-on #{:main :maps :carousel}}
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as r]
    [tolgraven.modules.strava.events]
    [tolgraven.modules.strava.subs]
    [tolgraven.modules.strava.views :as view]))

(def spec
  {:content [:strava]
   :server {:routes 'tolgraven.modules.strava.api/routes
            :requires-features {:main #{:services}}}
   :depends (get content-contract/module-dependencies :strava [])
   :id :strava
   :styles ["/css/tolgraven/modules/strava.min.css"]
   :view {:view #'view/<strava>}
   :init #(r/dispatch [:strava/init])})
