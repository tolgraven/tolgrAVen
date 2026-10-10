(ns tolgraven.dev-console.state
  "Bounded debug records. Views and automation both use the same subscriptions."
  (:require [clojure.string :as string]
            [tolgraven.react :as rf]))

(defonce *startup-capture? (atom false))

(def defaults
  {:recording? true
   :hydration-highlight? true
   :limit 500
   :page-capture? false
   :event-flash? false})
(defn options [db]
  (-> (merge (assoc defaults
                    :page-capture? @*startup-capture?
                    :hydration-highlight? (and ^boolean goog.DEBUG (:hydration-highlight? defaults)))
             (get-in db [:options :dev-console]))
      (update :limit #(if (number? %) (max 50 (min 2000 %)) 500))))
(defn snapshot [db]
  (let [debug (:dev-console db)]
    {:route (select-keys (:common/route db) [:path :path-params :query-params])
     :options (options db) :active (:active debug) :records (:records debug)}))
(rf/reg-sub :dev-console/snapshot (fn [db _] (snapshot db)))
(rf/reg-sub :dev-console/options (fn [db _] (options db)))
(rf/reg-sub :dev-console/data (fn [db _] (:dev-console db)))
(rf/reg-sub :dev-console/db
  (fn [db _] (-> db (dissoc :dev-console)
                 (update :component dissoc "tolgraven.dev-console.views"))))
(rf/reg-sub :dev-console/path (fn [db [_ path]] (get-in db path)))
(rf/reg-sub :dev-console/path-keys
  (fn [db query]
    (let [[_ path needle] (or (:re-frame/query-v query) query)
          value (get-in db path)]
      (->> (cond (map? value) (keys value)
                 (vector? value) (range (count value))
                 :else [])
           (filter #(string/starts-with? (pr-str %) (or needle "")))
           (take 10) vec))))
(rf/reg-event-db :dev-console/option
  (fn [db [_ key value]] (assoc-in db [:options :dev-console key] value)))
(defn bounded [limit before batch] (vec (take-last limit (concat before batch))))
(rf/reg-event-db :dev-console/records
  (fn [db [_ records]]
    (reduce (fn [db {:keys [kind instance] :as record}]
              (case kind
                :mount (assoc-in db [:dev-console :active instance] (dissoc record :kind))
                :unmount (-> (cond-> db
                               (and (= instance (get-in db [:dev-console :selected-instance]))
                                    (get-in db [:dev-console :active instance]))
                               (assoc-in [:dev-console :selected-record]
                                         (get-in db [:dev-console :active instance])))
                             (update-in [:dev-console :active] dissoc instance))
                :metadata (if (get-in db [:dev-console :active instance])
                            (update-in db [:dev-console :active instance] merge (dissoc record :kind)) db)
                (update-in db [:dev-console :records]
                           #(bounded (:limit (options db)) % [record])))) db records)))
(rf/reg-event-db :dev-console/disconnected
  (fn [db _] (update db :dev-console dissoc :active)))
(rf/reg-event-db :dev-console/clear
  (fn [db _] (assoc-in db [:dev-console :records] [])))
(rf/reg-event-db :dev-console/hydration-start
  (fn [db [_ token]] (assoc-in db [:state :debug :hydration-token] token)))
(rf/reg-event-db :dev-console/hydration-end
  (fn [db [_ token]]
    (if (= token (get-in db [:state :debug :hydration-token]))
      (update-in db [:state :debug] dissoc :hydration-token) db)))
(rf/reg-event-fx :dev-console/hydrated
  (fn [{:keys [db]} _]
    (when (:hydration-highlight? (options db))
      (let [token (random-uuid)]
        {:dispatch [:dev-console/hydration-start token]
         :dispatch-later [{:ms 150 :dispatch [:dev-console/hydration-end token]}]}))))

(rf/reg-sub :dev-console/flash
  (fn [db [_ instance]] (get-in db [:dev-console :flashes instance])))
(rf/reg-event-fx :dev-console/flash-instances
  (fn [{:keys [db]} [_ instances]]
    (let [token (inc (or (get-in db [:dev-console :flash-token]) 0))]
      {:db (-> db
               (assoc-in [:dev-console :flash-token] token)
               (update-in [:dev-console :flashes] #(merge % (zipmap instances (repeat token)))))
       :dispatch-later [{:ms 450 :dispatch [:dev-console/clear-flashes token]}]})))
(rf/reg-event-db :dev-console/clear-flashes
  (fn [db [_ token]]
    (update-in db [:dev-console :flashes]
      #(into {} (remove (fn [[_ value]] (= value token))) %))))
(rf/reg-event-db :dev-console/pick
  (fn [db [_ instance]]
    (-> db (assoc-in [:dev-console :selected-instance] instance)
        (assoc-in [:dev-console :selected-record]
                  (or (get-in db [:dev-console :active instance])
                      (when instance
                        (last (filter #(= instance (:instance %))
                                      (get-in db [:dev-console :records]))))))
        (assoc-in [:dev-console :picking?] false))))
(rf/reg-event-db :dev-console/picking
  (fn [db [_ value]] (assoc-in db [:dev-console :picking?] value)))

(rf/reg-event-db :dev-console/open
  (fn [db [_ value]] (assoc-in db [:dev-console :open?] value)))
(rf/reg-event-db :dev-console/toggle-open
  (fn [db _] (update-in db [:dev-console :open?] not)))

(rf/reg-event-fx :dev-console/flash-component
  (fn [_ [_ instance]] {:dev-console/flash-component instance}))
