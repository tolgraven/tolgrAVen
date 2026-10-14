(ns website.modules.instagram.module
  (:require
    [website.content.contract :as content-contract]
    [website.react :as r]
    [website.modules.instagram.events]
    [website.modules.instagram.subs]
    [website.modules.instagram.views :as view]))

(def spec
  {:server {:routes 'website.modules.instagram.api/routes
            :requires-features {:main #{:services}}}
   :depends (get content-contract/module-dependencies :instagram [])
   :id :instagram
   :styles ["/css/website/modules/instagram.min.css"]
   :view {:view #'view/<instagram>}
   :init #(r/dispatch [:instagram/init])})
