(ns website.modules.instagram.module
  (:require
    [website.react :as r]
    [website.modules.instagram.events]
    [website.modules.instagram.subs]
    [website.modules.instagram.views :as view]))

(def spec
  {:server {:routes 'website.modules.instagram.api/routes
            :requires-features {:main #{:services}}}
   :view {:view #'view/<instagram>}
   :init #(r/dispatch [:instagram/init])})
