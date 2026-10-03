(ns tolgraven.chat.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :chat/content
  :<- [:<-store :chat :messages]
  (fn [content]
    (->> content
         (map (fn [[id message]]
                (assoc message :id (if (keyword? id) (name id) (str id)))))
         (sort-by (juxt :time :id)))))

(rf/reg-sub :chat/latest-seq-id
  :<- [:chat/content]
  (fn [messages]
    ;; Only the Firebase fallback still allocates numeric IDs locally.
    (reduce max 0 (keep #(when (re-matches #"[0-9]+" (:id %))
                          (js/parseInt (:id %) 10)) messages))))
