(ns tolgraven.chat.module
  (:require
    [tolgraven.chat.events]
    [tolgraven.chat.subs]
    [tolgraven.chat.views :as view]))

(def spec
  {:id :chat
   :view {:view #'view/chat}})
