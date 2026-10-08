(ns tolgraven.modules.chat.subs
  (:require [tolgraven.react :as rf]))

(rf/reg-sub :chat/content
  :<- [:<-store :chat :messages]
  (fn [content]
    (->> content
         (map (fn [[id message]]
                (assoc message :id (if (keyword? id) (name id) (str id)))))
         (sort-by (juxt :time :id)))))
