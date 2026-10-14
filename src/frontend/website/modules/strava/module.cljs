(ns website.modules.strava.module
  (:require
    [website.react :as r]
    [website.modules.strava.events]
    [website.modules.strava.subs]
    [website.modules.strava.views :as view]))

(def spec
  {:bundle/depends-on #{:main :maps :carousel}
   :content [:strava]
   :server {:routes 'website.modules.strava.api/routes
            :requires-features {:main #{:services}}}
   :view {:view #'view/<strava>}
   :init #(r/dispatch [:strava/init])})
