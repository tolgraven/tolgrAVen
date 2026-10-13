(ns tolgraven.modules.gpt.subs
  (:require
    [tolgraven.react :as r]
    [clojure.string :as string]))

(r/reg-sub :gpt/thread
  (fn [[_ id]]
   (r/subscribe [:<-store-2 :gpt-threads (keyword (str id))]))
  (fn [thread [_ id]]
    (first (vals thread))))

(r/reg-sub :gpt/user-short
  (fn [[_ user]]
   (r/subscribe [:user/user user]))
  (fn [user [_ id]]
    (let [split (string/split (or (:name user) "anon") #"\s|-|_")]
      (if (< 1 (count split))
        (->> split
             (map string/capitalize)
             (map (partial take 1))
             flatten
             (apply str))
        (or (:name user) "anon"))) ))

(r/reg-sub :gpt/thread-ids :<- [:<-store-2 :gpt-threads]
  (fn [documents _] (sort (map name (keys documents)))))
