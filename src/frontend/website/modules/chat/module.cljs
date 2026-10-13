(ns website.modules.chat.module
  {:bundle/depends-on #{:main :user :link-preview}}
  (:require
    [website.modules.chat.storage :as storage]
    [website.content.contract :as content-contract]
    [website.modules.chat.events]
    [website.modules.chat.subs]
    [website.modules.chat.views :as view]))

(def spec
  {:server {:routes 'website.modules.chat.api/routes}
   :storage storage/tables
   :depends (get content-contract/module-dependencies :chat [])
   :id :chat
   :styles ["/css/website/modules/chat.min.css"]
   :view {:view #'view/<chat>}})
