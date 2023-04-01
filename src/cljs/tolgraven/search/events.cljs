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
       [:<-store [:typesense :auth] [:search/store-client]]})))

(rf/reg-event-fx :search/store-client [debug]
  (fn [{:keys [db]} [_ data]]
    {:db (assoc-in db [:search] data)}))

(def header-type "X-TYPESENSE-API-KEY")

(rf/reg-event-fx :search/state
  (fn [{:keys [db]} [_ path value]]
    {:db (assoc-in db (into [:state :search] path) value)}))

(defn get-host
  [host & [port]]
  (str (when-not (string/index-of host "https://") "https://") host
       (when port ":") port))

(rf/reg-event-fx :search/search
  (fn [{:keys [db]} [_ collection query query-by opts & [no-quote?]]]
    (when query ; search event sometimes sent with ""
     (let [{:keys [api_key host port]} (get-in db [:search :auth])
          query' (string/replace query #"(\w*)\s" "\"$1\" ")
          query-by (string/join "," query-by)
          url (str (get-host host port)
                   "/collections/" collection
                   "/documents/search")]
       {:db (-> db ; store directly instead of new event just to cut every ms
                (assoc-in [:search :previous-query collection]
                          (get-in db [:search :query collection])) ; store last query so can fallback results to it while loading
                (assoc-in [:search :query collection] query))
       :dispatch-n
       [(when-not (string/blank? query)
          [:http/get {:uri url
                      :headers {header-type api_key}
                      :url-params (merge {:q (if no-quote?
                                              query
                                              query')
                                          :query_by query-by}
                                         opts)}
           [:search/store-search-response [collection query] query-by opts no-quote?]
           [:diag/new :debug "Search error"]])
        #_[:search/latest-query collection query]]}))))


(rf/reg-event-fx :search/multi-search
  (fn [{:keys [db]} [_ collections query query-by opts & [no-quote?]]] ; might need support for different opts per collection
    (let [{:keys [api_key host port]} (get-in db [:search :auth])
          query-by (string/join "," query-by)
          url (str (get-host host port)
                   "multi_search")]
      {:dispatch-n
       [(when-not (string/blank? query)
          [:http/get {:uri url
                      :headers {header-type api_key}
                      :url-params {:searches (for [coll collections]
                                               (merge {:collection coll
                                                       :q query
                                                       :query_by query-by}
                                                      opts))}}
           [:search/store-search-response [collections query]]])
        [:search/latest-query collections query]]})))

(rf/reg-event-fx :search/store-search-response
  (fn [{:keys [db]} [_ path query-by opts no-quote? response]]
    (let [data (walk/keywordize-keys response)]
      (merge
       {:db (update-in db (into [:search :results] path) merge data)}
       (when (zero? (get-in response [:found]))
         {:dispatch [:search/no-results path query-by opts no-quote?]})))))

(rf/reg-event-fx :search/no-results
  (fn [{:keys [db]} [_ [collection query] query-by opts no-quote?]]
    (when-not no-quote?
      {:dispatch [:search/search collection query query-by opts true]})))

(rf/reg-event-fx :search/latest-query
  (fn [{:keys [db]} [_ collection query]]
    {:db (-> db
             (assoc-in [:search :previous-query collection]
                       (get-in db [:search :query collection])) ; store last query so can fallback results to it while loading
             (assoc-in [:search :query collection] query))}))

