(ns tolgraven.chat.subs
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub :chat/content
  :<- [:<-store :chat :messages]         
  (fn [content]
    (->> content
         (reduce-kv (fn [m k v]
                      (let [id (-> k name str js/parseInt)]
                        (assoc m id (assoc v :id id))))
                          {})
         (into (sorted-map))
         (map second))))

(rf/reg-sub :chat/latest-seq-id
  :<- [:chat/content]         
  (fn [messages]
    (apply max (map :id messages))))
