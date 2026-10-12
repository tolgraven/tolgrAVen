(ns tolgraven.modules.gpt.module
  {:bundle/depends-on #{:main :user}}
  (:require
    [tolgraven.modules.gpt.storage :as storage]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.modules.gpt.events]
    [tolgraven.modules.gpt.subs]
    [tolgraven.modules.gpt.views :as view]))

(def spec
  {:server {:routes 'tolgraven.modules.gpt.api/routes}
   :storage storage/tables
   :depends (get content-contract/module-dependencies :gpt [])
   :id :gpt
   :styles ["/css/tolgraven/modules/gpt.min.css"]
   :view {:view #'view/<threads>}})
