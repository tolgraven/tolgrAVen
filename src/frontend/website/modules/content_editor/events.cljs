(ns website.modules.content-editor.events
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.supabase.runtime :as transport]
            [website.content.contract :as contract]
            [website.content.fields :as fields]
            [website.content.client :as content])
  (:require-macros [website.build.site :refer [when-enabled]]))

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
    (if (and (current? db (owner db))
             (or (get-in db [:state :content-editor :enabled?])
                 (get-in db [:state :content-editor :settings :writable?])))
      (update-in db [:state :content-editor :enabled?] not) db)))

(r/reg-event-db :content-editor/change
  (fn [db [_ path expected value identities]]
    (if (current? db (owner db))
      (let [key (pr-str path)
            existing (get-in db [:state :content-editor :fields key])]
        (cond
          (or (= :saving (:status existing)) (= :saving (get-in db [:state :content-editor :cv-entry :status]))) db
          (= value (or expected "")) (update-in db [:state :content-editor :fields] dissoc key)
          :else (assoc-in db [:state :content-editor :fields key]
                    {:path path
                     :expected (if existing (:expected existing) expected)
                     :identities (or (:identities existing) identities [])
                     :value value
                     :status :draft}))) db)))

(r/reg-event-db :content-editor/discard
  (fn [db _]
    (if (fields/busy? (get-in db [:state :content-editor])) db
      (update-in db [:state :content-editor] #(-> % (assoc :fields {}) (dissoc :cv-entry))))))

(when-enabled :cv nil
  (do
    (r/reg-event-db :content-editor/cv-open
      (fn [db [_ column category count parents parent-keys column-id]]
        (if (and (current? db (owner db))
                 (nil? (get-in db [:state :content-editor :cv-entry]))
                 (not (fields/busy? (get-in db [:state :content-editor]))))
          (assoc-in db [:state :content-editor :cv-entry]
                    {:column column
                     :column-id column-id
                     :category category
                     :parents (or parents [])
                     :parent-keys (or parent-keys [])
                     :expected-count count
                     :fields {:kind "regular", :position "", :from "", :to "", :what "", :where "", :how ""}
                     :status :draft}) db)))
    (r/reg-event-db :content-editor/cv-change
      (fn [db [_ field value]]
        (if (and (current? db (owner db))
                 (#{:kind :position :from :to :what :where :how} field)
                 (get-in db [:state :content-editor :cv-entry])
                 (not= :saving (get-in db [:state :content-editor :cv-entry :status])))
          (assoc-in db [:state :content-editor :cv-entry :fields field] value) db)))
    (r/reg-event-db :content-editor/cv-cancel
      (fn [db _]
        (if (= :saving (get-in db [:state :content-editor :cv-entry :status])) db
          (update-in db [:state :content-editor] dissoc :cv-entry))))

    (defn cv-entry-value [{:keys [kind position from to what where how]} current-year]
      (let [from' (when (re-matches #"[0-9]{4}" from) (js/parseInt from 10))
            to' (when (re-matches #"[0-9]{4}" to) (js/parseInt to 10))
            what' (string/trim what)
            how' (->> (string/split-lines how) (map string/trim) (remove string/blank?) vec)]
        (when (and (#{"regular" "container"} kind) from' (<= 1600 from' 3000)
                   (or (and (string/blank? to) (<= from' (inc current-year)))
                       (and to' (<= from' to' 3000)))
                   (<= 1 (count what') 200) (<= (count where) 200) (<= (count position) 2000)
                   (<= (count how') 32) (every? #(<= (count %) 2000) how'))
          {:kind kind, :position position, :from from', :to to', :what what', :where (string/trim where), :how how'})))

    (r/reg-event-fx :content-editor/cv-create
      (fn [{:keys [db]} [_ current-year]]
        (let [account (owner db)
              editor (get-in db [:state :content-editor])
              draft (:cv-entry editor)
              entry (when draft (cv-entry-value (:fields draft) current-year))]
          (when (and (current? db account) draft (not (fields/busy? editor)))
            (if (and entry (empty? (:fields editor))
                     (or (= "regular" (:kind entry)) (< (count (:parents draft)) 6)))
              {:db (assoc-in db [:state :content-editor :cv-entry :status] :saving)
               :content-editor/request {:method :post
                                        :uri "/api/content/cv-entry"
                                        :data (assoc (select-keys draft [:column :column-id :category :parents :parent-keys :expected-count]) :entry entry)
                                        :success [:content-editor/cv-created account]
                                        :failure [:content-editor/cv-failed account]}}
              {:db (assoc-in db [:state :content-editor :cv-entry :error]
                             (if (seq (:fields editor))
                               "Save or discard your other changes before creating an entry."
                               "Enter a start year, a title, and an end year no earlier than the start."))})))))
    (r/reg-event-fx :content-editor/cv-created
      (fn [{:keys [db]} [_ account bundle]]
        (when (and (current? db account) (get-in db [:state :content-editor :cv-entry]))
          (if (content/valid-bundle? bundle [:cv])
            {:db (update-in db [:state :content-editor] dissoc :cv-entry)
             :dispatch [:content/install bundle]}
            {:dispatch [:content-editor/cv-failed account {:message "Invalid creation response"}]}))))
    (r/reg-event-fx :content-editor/cv-failed
      (fn [{:keys [db]} [_ account error]]
        (when (and (current? db account) (get-in db [:state :content-editor :cv-entry]))
          (let [bundle (get-in error [:response :content-bundle])
                conflict? (and bundle (content/valid-bundle? bundle [:cv]))]
            (cond-> {:db (update-in db [:state :content-editor :cv-entry]
                                   #(assoc %
                                           :status :error
                                           :conflict? (boolean conflict?)
                                           :error (or (get-in error [:response :error]) (:message error)
                                                      "Unable to create the entry. Your draft is retained.")))}
              conflict? (assoc :dispatch [:content/install bundle]))))))))


(r/reg-event-fx :content-editor/save-all
  (fn [{:keys [db]} _]
    (when (and (current? db (owner db))
               (not (fields/busy? (get-in db [:state :content-editor]))))
      (let [queue (->> (get-in db [:state :content-editor :fields])
                       (filter (fn [[_ draft]] (not= (:expected draft) (:value draft))))
                       (map first)
                       sort
                       vec)]
        {:db (assoc-in db [:state :content-editor :queue] queue)
         :dispatch [:content-editor/save-next]}))))

(r/reg-event-fx :content-editor/save-next
  (fn [{:keys [db]} _]
    (when (current? db (owner db))
      (when-let [key (first (get-in db [:state :content-editor :queue]))]
        {:db (update-in db [:state :content-editor :queue] #(vec (rest %)))
         :dispatch [:content-editor/save key]}))))
(r/reg-event-fx :content-editor/save
  (fn [{:keys [db]} [_ key]]
    (let [draft (get-in db [:state :content-editor :fields key])
          account (owner db)]
      (when (and (current? db account) draft (not (#{:saving :uploading} (:status draft))))
        {:db (assoc-in db [:state :content-editor :fields key :status] :saving)
         :content-editor/request {:method :put
                                  :uri "/api/content/field"
                                  :data {:path (mapv #(if (keyword? %) (name %) %) (:path draft))
                                         :identities (mapv #(update % :path (fn [path] (mapv (fn [part] (if (keyword? part) (name part) part)) path))) (:identities draft))
                                         :expected (:expected draft)
                                         :value (:value draft)}
                                  :success [:content-editor/saved account key]
                                  :failure [:content-editor/failed account key]}}))))
(r/reg-event-fx :content-editor/saved
  (fn [{:keys [db]} [_ account key bundle]]
    (when (current? db account)
      (if (content/valid-bundle? bundle [(first (get-in db [:state :content-editor :fields key :path]))])
        {:db (update-in db [:state :content-editor :fields] dissoc key)
         :dispatch-n [[:content/install bundle] [:content-editor/save-next]]}
        {:dispatch [:content-editor/failed account key {:message "Invalid save response"}]}))))
(r/reg-event-fx :content-editor/failed
  (fn [{:keys [db]} [_ account key error]]
    (when (and (current? db account) (get-in db [:state :content-editor :fields key]))
      (let [bundle (get-in error [:response :content-bundle])
            section (first (get-in db [:state :content-editor :fields key :path]))
            conflict? (and bundle (content/valid-bundle? bundle [section]))
            saving? (= :saving (get-in db [:state :content-editor :fields key :status]))]
        {:db (update-in db [:state :content-editor :fields key]
                       #(assoc %
                               :status :error
                               :conflict? (boolean conflict?)
                               :error (or (get-in error [:response :error]) (:message error)
                                          "Unable to save. Your draft is retained.")))
         :dispatch-n (cond-> []
                       conflict? (conj [:content/install bundle])
                       saving? (conj [:content-editor/save-next]))}))))

(r/reg-event-db :content-editor/discard-field
  (fn [db [_ key]]
    (if (and (current? db (owner db))
             (not= :saving (get-in db [:state :content-editor :fields key :status])))
      (update-in db [:state :content-editor :fields] dissoc key) db)))

;; Native File/FormData and async media status stay at the registered effect
;; boundary. The existing durable media pipeline owns conversion/publication.
(r/reg-fx :content-editor/upload
  (fn [{:keys [file account key]}]
    (let [data (js/FormData.)]
      (.append data "file" file)
      (r/dispatch [:content-editor/upload-request account key data]))))
(r/reg-event-fx :content-editor/upload-request
  (fn [{:keys [db]} [_ account key data]]
    (when (and (current? db account)
               (= :uploading (get-in db [:state :content-editor :fields key :status])))
      {:content-editor/request {:method :post
                                :uri "/api/supabase/media"
                                :data data
                                :success [:content-editor/uploaded account key]
                                :failure [:content-editor/failed account key]}})))
(r/reg-event-fx :content-editor/upload
  (fn [{:keys [db]} [_ path expected file identities]]
    (let [key (pr-str path)
          account (owner db)
          existing (get-in db [:state :content-editor :fields key])]
      (when (and (current? db account) (not (fields/busy? (get-in db [:state :content-editor]))))
        {:db (assoc-in db [:state :content-editor :fields key]
                      {:path path
                       :expected (if existing (:expected existing) expected)
                       :identities (or (:identities existing) identities [])
                       :value (or expected "")
                       :status :uploading})
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
