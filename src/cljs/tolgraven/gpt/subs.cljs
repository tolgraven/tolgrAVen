(ns tolgraven.gpt.subs
  (:require
    [re-frame.core :as rf]
    [clojure.string :as string]))

(rf/reg-sub :gpt/thread
  (fn [[_ id]]
   (rf/subscribe [:<-store-2 :gpt-threads (keyword (str id))]))
  (fn [thread [_ id]]
    (first (vals thread))))

(rf/reg-sub :gpt/user-short
  (fn [[_ user]]
   (rf/subscribe [:user/user user]))
  (fn [user [_ id]]
    (let [split (string/split (or (:name user) "anon") #"\s|-|_")]
      (if (< 1 (count split))
        (->> split
             (map string/capitalize)
             (map (partial take 1))
             flatten
             (apply str))
        (or (:name user) "anon"))) ))

(rf/reg-sub :gpt/thread-ids :<- [:<-store-2 :gpt-threads]
  (fn [documents _] (sort (map name (keys documents)))))
