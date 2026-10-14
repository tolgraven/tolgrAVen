(ns website.modules.content-editor.events
  (:require [website.react :as r]
            [website.supabase.runtime :as transport]
            [website.content.contract :as contract]
            [website.content.client :as content]))

(defn owner [db] (get-in db [:state :active-user :id]))
(defn admin? [db] (boolean (some #{"admins"} (get-in db [:state :active-user :roles]))))
(defn current? [db account]
  (and account (= account (owner db)) (admin? db)
       (= account (get-in db [:state :content-editor :owner]))))
(defn request! [{:keys [method uri data success failure]}]
  (-> (transport/authenticated-request! method uri data
        #(r/dispatch (conj success %))
        #(r/dispatch (conj failure %)))
      (.catch #(r/dispatch (conj failure {:message "The content service could not initialize"})))))
(r/reg-fx :content-editor/request request!)

(r/reg-event-fx :content-editor/open
  (fn [{:keys [db]} _]
    (when (admin? db)
      (let [account (owner db)]
        {:db (if (= account (get-in db [:state :content-editor :owner])) db
               (assoc-in db [:state :content-editor] {:owner account, :fields {}}))
         :content-editor/request {:method :get
                                  :uri "/api/content/editor"
                                  :success [:content-editor/settings account]
                                  :failure [:content-editor/settings-error account]}}))))

(r/reg-event-db :content-editor/settings
  (fn [db [_ account settings]]
    (if (current? db account)
      (-> db (assoc-in [:state :content-editor :settings] settings)
          (update-in [:state :content-editor] dissoc :error)) db)))
(r/reg-event-db :content-editor/settings-error
  (fn [db [_ account _]]
    (if (current? db account)
      (assoc-in db [:state :content-editor :error] "Content editing is unavailable. Try again.") db)))
(r/reg-event-db :content-editor/toggle
  (fn [db _]
    (if (and (current? db (owner db)) (get-in db [:state :content-editor :settings :writable?]))
      (update-in db [:state :content-editor :enabled?] not) db)))

(r/reg-event-db :content-editor/change
  (fn [db [_ path expected value]]
    (if (current? db (owner db))
      (let [key (pr-str path)
            existing (get-in db [:state :content-editor :fields key])]
        (if (= :saving (:status existing)) db
          (assoc-in db [:state :content-editor :fields key]
                    {:path path, :expected (or (:expected existing) expected), :value value, :status :draft}))) db)))

(r/reg-event-db :content-editor/discard
  (fn [db _]
    (if (some #(#{:saving :uploading} (:status %)) (vals (get-in db [:state :content-editor :fields]))) db
      (assoc-in db [:state :content-editor :fields] {}))))

(r/reg-event-fx :content-editor/save-all
  (fn [{:keys [db]} _]
    (when (current? db (owner db))
      {:dispatch-n (mapv (fn [[key draft]] [:content-editor/save key])
                        (filter (fn [[_ draft]] (and (not (#{:saving :uploading} (:status draft)))
                                                    (not= (:expected draft) (:value draft))))
                                (get-in db [:state :content-editor :fields])))})))
(r/reg-event-fx :content-editor/save
  (fn [{:keys [db]} [_ key]]
    (let [draft (get-in db [:state :content-editor :fields key])
          account (owner db)]
      (when (and (current? db account) draft (not (#{:saving :uploading} (:status draft))))
        {:db (assoc-in db [:state :content-editor :fields key :status] :saving)
         :content-editor/request {:method :put
                                  :uri "/api/content/field"
                                  :data {:path (mapv #(if (keyword? %) (name %) %) (:path draft))
                                         :expected (:expected draft)
                                         :value (:value draft)}
                                  :success [:content-editor/saved account key]
                                  :failure [:content-editor/failed account key]}}))))
(r/reg-event-fx :content-editor/saved
  (fn [{:keys [db]} [_ account key bundle]]
    (when (current? db account)
      (if (content/valid-bundle? bundle [(first (get-in db [:state :content-editor :fields key :path]))])
        {:db (update-in db [:state :content-editor :fields] dissoc key)
         :dispatch [:content/install bundle]}
        {:dispatch [:content-editor/failed account key {:message "Invalid save response"}]}))))
(r/reg-event-db :content-editor/failed
  (fn [db [_ account key error]]
    (if (and (current? db account) (get-in db [:state :content-editor :fields key]))
      (update-in db [:state :content-editor :fields key]
                 #(assoc % :status :error :error (or (get-in error [:response :error]) (:message error)
                                                     "Unable to save. Your draft is retained."))) db)))

;; Native File/FormData and async media status stay at the registered effect
;; boundary. The existing durable media pipeline owns conversion/publication.
(r/reg-fx :content-editor/upload
  (fn [{:keys [file account key]}]
    (let [data (js/FormData.)]
      (.append data "file" file)
      (request! {:method :post :uri "/api/supabase/media" :data data
                 :success [:content-editor/uploaded account key]
                 :failure [:content-editor/failed account key]}))))
(r/reg-event-fx :content-editor/upload
  (fn [{:keys [db]} [_ path expected file]]
    (let [key (pr-str path)
          account (owner db)
          existing (get-in db [:state :content-editor :fields key])]
      (when (and (current? db account) (not (#{:saving :uploading} (:status existing))))
        {:db (assoc-in db [:state :content-editor :fields key]
                      {:path path, :expected (or (:expected existing) expected), :value expected, :status :uploading})
         :content-editor/upload {:file file, :account account, :key key}}))))
(r/reg-event-fx :content-editor/uploaded
  (fn [{:keys [db]} [_ account key upload]]
    (when (and (current? db account) (= :uploading (get-in db [:state :content-editor :fields key :status])))
      (case (:status upload)
        "ready" {:db (update-in db [:state :content-editor :fields key]
                               #(assoc % :value (get-in upload [:asset :src]) :status :draft))}
        "failed" {:dispatch [:content-editor/failed account key {:message (:error upload)}]}
        {:db (assoc-in db [:state :content-editor :fields key :upload-id] (:id upload))
         :dispatch-later [{:ms 1000, :dispatch [:content-editor/poll account key (:id upload)]}]}))))
(r/reg-event-fx :content-editor/poll
  (fn [{:keys [db]} [_ account key id]]
    (when (and (current? db account) (= id (get-in db [:state :content-editor :fields key :upload-id]))
               (= :uploading (get-in db [:state :content-editor :fields key :status])))
      {:content-editor/request {:method :get
                               :uri (str "/api/supabase/media/" id)
                               :success [:content-editor/uploaded account key]
                               :failure [:content-editor/failed account key]}})))
