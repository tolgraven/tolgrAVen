(ns tolgraven.search.subs
  (:require
   [re-frame.core :as rf]
   [clojure.string :as string]
   [clojure.walk :as walk]))

(rf/reg-sub :search/get
 (fn [db [_ path]]
   (get-in db (into [:search] path))))

(rf/reg-sub :search/results
 (fn [db [_ collection]]
   (get-in db [:search :results collection])))

(rf/reg-sub :search/get-query
 (fn [db [_ collection]]
   (get-in db [:search :query collection])))

(rf/reg-sub :search/previous-query
 (fn [db [_ collection]]
   (get-in db [:search :previous-query collection])))

(rf/reg-sub :search/state
 (fn [db [_ path]]
   (get-in db (into [:state :search] path))))

(rf/reg-sub :search/open?
 (fn [db [_ path]]
   (get-in db [:state :search :open?])))

(rf/reg-sub
 :search/results-for-query
 (fn [[_ collection & [maybe-query]]]
   [(rf/subscribe [:search/results collection])
    (rf/subscribe [:search/get-query collection])
    (rf/subscribe [:search/previous-query collection])])
 (fn [[data new-query last-query] [_ collection & [maybe-query]]]
   (let [query (or maybe-query new-query)]
     (when-not (string/blank? query)
       (get-in data [query]
               (get-in data [last-query])))))) ;show old results until new are ready! doesn't really help when typing rapidly heh

(rf/reg-sub
 :search/snippets-for-query
 (fn [[_ collection & [maybe-query]]]
   (rf/subscribe [:search/results-for-query collection maybe-query]))
 (fn [results [_ collection & [maybe-query]]]
   (->> (:hits results)
        (map :highlights)
        flatten
        (map :snippet))))

