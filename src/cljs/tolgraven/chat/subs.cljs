(ns tolgraven.chat.subs
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub :chat/content
  :<- [:<-store :chat :messages]         
  (fn [content]
    (map second
         (into (sorted-map)
               (reduce-kv (fn [m k v]
                            (assoc m (-> k name str js/parseInt) v))
                          {}
                          content)))))

(rf/reg-sub :chat/latest-seq-id
  :<- [:<-store :chat :messages]         
  (fn [messages]
    (apply max (map #(-> % first name str js/parseInt) messages))))
