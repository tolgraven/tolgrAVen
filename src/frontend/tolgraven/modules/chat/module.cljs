(ns tolgraven.modules.chat.module
  {:bundle/depends-on #{:main :user :link-preview}}
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.modules.chat.events]
    [tolgraven.modules.chat.subs]
    [tolgraven.modules.chat.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :chat [])
   :id :chat
   :view {:view #'view/<chat>}})
