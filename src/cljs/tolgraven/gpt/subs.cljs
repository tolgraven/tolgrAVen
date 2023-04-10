(ns tolgraven.gpt.subs
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub :gpt/content
  :<- [:<-store :gpt :messages]         
  (fn [content]
    (->> content
         (reduce-kv (fn [m k v]
                      (let [id (-> k name str js/parseInt)]
                        (assoc m id (assoc v :id id))))
                          {})
         (into (sorted-map))
         vals)))

(rf/reg-sub :gpt/latest-seq-id
  :<- [:gpt/content]         
  (fn [messages]
    (let [id (apply max (map :id messages))]
      (if (number? id)
        id
        0))))
