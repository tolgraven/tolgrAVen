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


(rf/reg-sub :gpt/thread
  (fn [[_ id]]
   (rf/subscribe [:<-store-2 :gpt-threads (keyword (str id))]))
  (fn [thread [_ id]]
    (first (vals thread))))

; TODO use :gpt :threads :ids and just sub array and pass it back in
; (but also implement support for the fb function to append to an array lol)
(rf/reg-sub :gpt/thread-ids
  :<- [:<-store-2 :gpt-threads]         
  (fn [m [_ id]]
    (->> m keys (map name) (map js/parseInt))))

(rf/reg-sub :gpt/new-thread-id
  :<- [:gpt/thread-ids]         
  (fn [ids]
    (inc (or (apply max ids) 0))))
