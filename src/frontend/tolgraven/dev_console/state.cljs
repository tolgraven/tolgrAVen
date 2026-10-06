(ns tolgraven.dev-console.state
  "Bounded debug records. Views and automation both use the same subscriptions."
  (:require [clojure.string :as string]
            [tolgraven.react :as rf]))

(def defaults {:recording? true :hydration-highlight? true :limit 500})
(defn options [db]
  (-> (merge (update defaults :hydration-highlight? #(and ^boolean goog.DEBUG %))
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
                :unmount (update-in db [:dev-console :active] dissoc instance)
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
