(ns tolgraven.search.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojure.string :as string]
    [clojure.walk :as walk]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(rf/reg-event-fx :search/init
 (fn [{:keys [db]} [_ ]]
   (when-not (get-in db [:search])
      {:dispatch
       [:<-store [:secrets :typesense] [:search/store-client]]})))

(rf/reg-event-fx :search/store-client [debug]
  (fn [{:keys [db]} [_ data]]
    {:db (assoc-in db [:search] data)}))

(def header-type "X-TYPESENSE-API-KEY")

(rf/reg-event-fx :search/search
  (fn [{:keys [db]} [_ collection query query-by opts]]
    (let [{:keys [api_key host port]} (get-in db [:search :typesense])
          query-by (string/join "," query-by)
          url (str (if (string/index-of host "https://")
                     ""
                     "https://")
                   host ":" port
                   "/collections/" collection
                   "/documents/search")]
      {:dispatch-n
       [[:http/get {:uri url
                    :headers {header-type api_key}
                    :url-params (merge {:q query
                                        :query_by query-by}
                                       opts)}
         [:search/store-search-response [collection query]]]
        [:search/latest-query collection query]]})))



(rf/reg-event-fx :search/store-search-response [debug]
  (fn [{:keys [db]} [_ path response]]
    (let [data (walk/keywordize-keys response)]
      {:db (update-in db (into [:search :results] path) merge data)})))

(rf/reg-event-fx :search/latest-query
  (fn [{:keys [db]} [_ collection query]]
    {:db (-> db
             (assoc-in [:search :previous-query collection]
                       (get-in db [:search :query collection])) ; store last query so can fallback results to it while loading
             (assoc-in [:search :query collection] query))}))

