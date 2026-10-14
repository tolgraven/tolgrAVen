(ns website.modules.strava.module
  {:bundle/depends-on #{:main :maps :carousel}}
  (:require
    [website.content.contract :as content-contract]
    [website.react :as r]
    [website.modules.strava.events]
    [website.modules.strava.subs]
    [website.modules.strava.views :as view]))

(def spec
  {:content [:strava]
   :server {:routes 'website.modules.strava.api/routes
            :requires-features {:main #{:services}}}
   :depends (get content-contract/module-dependencies :strava [])
   :id :strava
   :styles ["/css/website/modules/strava.min.css"]
   :view {:view #'view/<strava>}
   :init #(r/dispatch [:strava/init])})
