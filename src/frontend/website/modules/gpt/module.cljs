(ns website.modules.gpt.module
  {:bundle/depends-on #{:main :user}}
  (:require
    [website.modules.gpt.storage :as storage]
    [website.content.contract :as content-contract]
    [website.modules.gpt.events]
    [website.modules.gpt.subs]
    [website.modules.gpt.views :as view]))

(def spec
  {:server {:routes 'website.modules.gpt.api/routes}
   :storage storage/tables
   :depends (get content-contract/module-dependencies :gpt [])
   :id :gpt
   :styles ["/css/tolgraven/modules/gpt.min.css"]
   :view {:view #'view/<threads>}})
