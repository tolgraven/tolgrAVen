(ns website.modules.search.events
  (:require [website.react :as r]
            [clojure.string :as string]
            [website.modules.search.schema :as schema]
            [website.modules.search.model :as model]
            [website.loader :as loader]
            [website.validation :as validation]))

(r/reg-event-db :search/init
  (fn [db _]
    (update db :search #(if (string? (:query %))
                         (merge schema/initial (select-keys % (keys schema/initial)))
                         schema/initial))))

(r/reg-event-fx :search/state
  (fn [{:keys [db]} [_ path value]]
    (let [search (if (string? (get-in db [:search :query]))
                   (merge schema/initial (select-keys (:search db) (keys schema/initial)))
                   schema/initial)
          db' (-> db (assoc :search search) (assoc-in (into [:state :search] path) value))]
      (if (= path [:open?])
        (if value
          {:db db'
           :dispatch-n [[:loader/activate {:module :search}]
                        [:search/query (:query search)]]}
          {:db (-> db'
                   (update-in [:search :generation] (fnil inc 0))
                   (assoc-in [:search :pending?] false)
                   (assoc-in [:search :selected] -1))})
        {:db db'}))))

(r/reg-event-fx :search/query
  (fn [{:keys [db]} [_ query]]
    (let [query' (subs (or query "") 0 (min 200 (count (or query ""))))
          generation (inc (get-in db [:search :generation] 0))
          blank? (string/blank? query')]
      (cond-> {:db (update db :search #(merge schema/initial (select-keys % (keys schema/initial))
                           {:query query'
                            :generation generation
                            :pending? (not blank?)
                            :selected -1
                            :error nil}))}
        blank? (update :db assoc-in [:search :response] nil)
        (not blank?) (assoc :dispatch-later [{:ms 180, :dispatch [:search/request generation query' 1]}])))))

(r/reg-fx :search/prepare-request
  (fn [[generation query page]]
    ;; Reuse the shared code/install boundary when Search is opened before boot's
    ;; deferred coercion acquisition. The generation still guards its completion.
    (-> (loader/acquire-code! :coercion)
        (.then #(r/dispatch [:search/request generation query page]))
        (.catch #(r/dispatch [:search/failed generation query nil])))))

(r/reg-event-fx :search/request
  (fn [{:keys [db]} [_ generation query page]]
    (when (and (= generation (get-in db [:search :generation]))
               (= query (get-in db [:search :query]))
               (not (string/blank? query)))
      (if-not (validation/ready?)
        {:search/prepare-request [generation query page]}
      {:db (assoc-in db [:search :pending?] true)
       :dispatch [:http/get {:uri "/api/integrations/search"
                             :background? true
                             :url-params {:q query, :page page, :per_page 12}}
                  [:search/received generation query]
                  [:search/failed generation query]]}))))

(r/reg-event-fx :search/submit
  (fn [{:keys [db]} _]
    (let [generation (inc (get-in db [:search :generation] 0))
          query (get-in db [:search :query] "")]
      {:db (assoc-in db [:search :generation] generation)
       :dispatch [:search/request generation query 1]})))

(r/reg-event-db :search/received
  (fn [db [_ generation query response]]
    (if (and (= generation (get-in db [:search :generation]))
             (= query (get-in db [:search :query])))
      (let [response' (if (map? response)
                        (update response :hits
                                #(when (sequential? %)
                                   (mapv (fn [hit]
                                           (if (map? hit)
                                             (update hit :preview (fn [value] (if (string? value) (keyword value) value)))
                                             hit)) %)))
                        response)]
        (if (and (= query (:query response')) (nil? (validation/explain model/result response')))
          (update db :search merge {:response response', :pending? false, :error nil, :selected -1})
          (update db :search merge {:pending? false, :error "Search returned an invalid response"})))
      db)))

(r/reg-event-db :search/failed
  (fn [db [_ generation query _error]]
    (if (and (= generation (get-in db [:search :generation]))
             (= query (get-in db [:search :query])))
      (update db :search merge {:pending? false, :error "Search is temporarily unavailable. Try again."})
      db)))

(r/reg-event-db :search/select
  (fn [db [_ step]]
    (let [response (get-in db [:search :response])
          suggestions (when (= (:query response) (get-in db [:search :query])) (:suggestions response))
          selected (get-in db [:search :selected] -1)
          amount (count suggestions)]
      (assoc-in db [:search :selected]
                (cond (zero? amount) -1
                      (neg? selected) (if (pos? step) 0 (dec amount))
                      :else (mod (+ selected step) amount))))))

