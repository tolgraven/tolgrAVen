(ns tolgraven.chat.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.chat.events]
    [tolgraven.chat.subs]
    [tolgraven.chat.views :as view]))

(def spec
  {:content (get content-contract/module-content :chat [])
   :id :chat
   :view {:view #'view/<chat>}})
