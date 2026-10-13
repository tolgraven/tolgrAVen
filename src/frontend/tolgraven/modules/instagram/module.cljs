(ns tolgraven.modules.instagram.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as r]
    [tolgraven.modules.instagram.events]
    [tolgraven.modules.instagram.subs]
    [tolgraven.modules.instagram.views :as view]))

(def spec
  {:server {:routes 'tolgraven.modules.instagram.api/routes
            :requires-features {:main #{:services}}}
   :depends (get content-contract/module-dependencies :instagram [])
   :id :instagram
   :styles ["/css/tolgraven/modules/instagram.min.css"]
   :view {:view #'view/<instagram>}
   :init #(r/dispatch [:instagram/init])})
