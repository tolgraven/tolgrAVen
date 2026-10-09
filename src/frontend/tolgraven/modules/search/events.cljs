(ns tolgraven.modules.search.events
  (:require
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [clojure.string :as string]
    [clojure.walk :as walk]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(rf/reg-event-fx :search/init
  (fn [{:keys [db]} _] {:db (update db :search #(or % {}))}))

(rf/reg-event-fx :search/state
  (fn [{:keys [db]} [_ path value]]
    {:db (assoc-in db (into [:state :search] path) value)}))

(rf/reg-event-fx :search/search
  (fn [{:keys [db]} [_ collection query _ opts & _]]
    (when (string? query)
      (cond-> {:db (-> db
                      (assoc-in [:search :previous-query collection] (get-in db [:search :query collection]))
                      (assoc-in [:search :query collection] query))}
        (not (string/blank? query))
        (assoc :dispatch
               [:http/get {:uri "/api/integrations/search"
                           :url-params (merge (select-keys opts [:page :per_page]) {:collection collection :q query})}
                [:search/store-search-response [collection query]]
                [:diag/new :debug "Search error"]])))))

(rf/reg-event-fx :search/multi-search
  (fn [_ [_ collections query query-by opts no-quote?]]
    {:dispatch-n (mapv #(vector :search/search % query query-by opts no-quote?) collections)}))

(rf/reg-event-db :search/store-search-response
  (fn [db [_ path response]]
    (assoc-in db (into [:search :results] path) (walk/keywordize-keys response))))

(rf/reg-event-fx :search/latest-query
  (fn [{:keys [db]} [_ collection query]]
    {:db (-> db
             (assoc-in [:search :previous-query collection]
                       (get-in db [:search :query collection])) ; store last query so can fallback results to it while loading
             (assoc-in [:search :query collection] query))}))

