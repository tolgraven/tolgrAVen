(ns tolgraven.chat.subs
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub :chat/content
  (fn [_ _]
    (rf/subscribe [:<-store :chat :messages]))
  (fn [content]
    (map second content)))

(rf/reg-sub :chat/latest-seq-id
  (fn [_ _]
    (rf/subscribe [:<-store :chat :messages]))
  (fn [messages]
    (apply max (map #(-> % first name str js/parseInt) messages))))
