(ns tolgraven.chat.subs
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub :chat/content
  :<- [:<-store :chat :messages]         
  (fn [content]
    (map second content)))

(rf/reg-sub :chat/latest-seq-id
  :<- [:<-store :chat :messages]         
  (fn [messages]
    (apply max (map #(-> % first name str js/parseInt) messages))))
