(ns tolgraven.chat.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.chat.events]
    [tolgraven.chat.subs]
    [tolgraven.chat.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :chat [])
   :id :chat
   :view {:view #'view/<chat>}})
