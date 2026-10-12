(ns tolgraven.modules.chat.module
  {:bundle/depends-on #{:main :user :link-preview}}
  (:require
    [tolgraven.modules.chat.storage :as storage]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.modules.chat.events]
    [tolgraven.modules.chat.subs]
    [tolgraven.modules.chat.views :as view]))

(def spec
  {:server {:routes 'tolgraven.modules.chat.api/routes}
   :storage storage/tables
   :depends (get content-contract/module-dependencies :chat [])
   :id :chat
   :styles ["/css/tolgraven/modules/chat.min.css"]
   :view {:view #'view/<chat>}})
