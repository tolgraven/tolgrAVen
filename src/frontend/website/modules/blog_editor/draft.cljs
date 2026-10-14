(ns website.modules.blog-editor.draft
  "Account-owned working/named drafts reuse the existing storage debounce and pagehide flush."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.component.storage :as storage]
            [website.validation :as validation]
            [website.schema.common :as common]
            [website.media.query :as media-query]
            [website.modules.blog-editor.draft-schema :as schema]
            [website.modules.blog-editor.model :as model]))

(def form-path [:state :form-field :post-blog])
(def state-path [:state :blog-editor :draft])
(def form-fields [:title :text :tags :image-assets])
(def options {:scope :user, :consume? false, :version 1, :ttl-ms (* 30 24 60 60 1000)})
(def collection-id :blog-editor/drafts)
(defn collection-id-for [id]
  ;; New-post drafts share a chooser. Published-post revisions remain with
  ;; their existing edit target, never inherit another post's Submit target.
  (if (= :new (second id)) collection-id [:blog-editor/drafts (second id)]))
(defn working-id [db] [:blog-editor/working (or (get-in db [:state :blog :editing :id]) :new)])
(defn account-id [db] (get-in db [:state :active-user :id]))
(defn active-owner? [db owner] (= owner (get-in db [:state :blog-editor :active-owner])))
(defn private-owner? [account] (= account (second @storage/*identity)))

(defn canonical-form [db owner]
  (let [form (get-in db form-path)
        text (or (:text form) "")
        references (set (media-query/markdown-sources text))
        ready (filter #(and (= owner (:owner %)) (= :ready (:status %)))
                      (vals (get-in db [:state :blog :image-uploads])))
        assets (merge (:image-assets form) (into {} (map (juxt #(get-in % [:asset :src]) :asset)) ready))]
    {:title (or (:title form) "")
     :text text
     :tags (or (:tags form) "")
     :image-assets (select-keys assets references)}))

(defn empty-form? [form]
  (and (string/blank? (:title form)) (string/blank? (:text form)) (empty? (:tags form))))

(defn projection [db owner]
  (let [{:keys [account document-id named? entries status] :as state} (get-in db state-path)
        form (canonical-form db owner)]
    (when (and (= owner (:owner state)) (active-owner? db owner) account
               (map? (get-in db form-path))
               document-id (not= :loading status) (nil? (validation/explain schema/form form)))
      {:working-id (:working-id state)
       :working (cond-> {:owner-id account, :document-id document-id, :named? (boolean named?)}
                  (not named?) (assoc :form form))
       :collection {:owner-id account
                    :entries (cond-> (or entries {})
                               named? (assoc document-id {:id document-id, :form form}))}})))

(r/reg-sub :blog-editor/draft-projection
  (fn [db query]
    (let [[_ owner] (or (:re-frame/query-v query) query)]
      (projection db owner))))

(defn current-request? [db owner account token]
  (and (active-owner? db owner) (= account (account-id db))
       (= token (get-in db (conj state-path :token)))
       (= owner (get-in db (conj state-path :owner)))))

(defn validated-read [id contract account]
  (let [value (:value (storage/read! id options))]
    (when value
      (when (or (not= account (:owner-id value)) (validation/explain contract value))
        (throw (js/Error. "The saved draft could not be read. Your current text is unchanged.")))
      value)))

(r/reg-fx :blog-editor/read-drafts
  (fn [{:keys [id owner account token before restore? document-id]}]
    (let [identity @storage/*identity]
      (-> (storage/retry-read! options)
          (.then (fn [_]
                   (when-not (and identity (= identity @storage/*identity) (private-owner? account))
                     (throw (js/Error. "Private draft storage is not ready. Sign in and retry.")))
                   (when (storage/read-failed? options)
                     (throw (js/Error. "Private drafts could not be read. Your current text and saved records are unchanged. Retry storage.")))
                   (r/dispatch [:blog/drafts-read owner account token id before restore? document-id
                                (validated-read id schema/working account)
                                (validated-read (collection-id-for id) schema/collection account)])))
          (.catch (fn [error] (r/dispatch [:blog/draft-failed owner account token (.-message error)])))))))

(r/reg-fx :blog-editor/track-drafts
  (fn [{:keys [id owner account]}]
    ;; Reads project the accepted canonical form through a normal subscription.
    ;; There is no copied working form, editor tree, timer or second write queue.
    (doseq [[storage-id field] [[id :working] [(collection-id-for id) :collection]]]
      (storage/track! storage-id
        (fn []
          (let [projected @(r/sub [:blog-editor/draft-projection owner])]
            (or (when (and (private-owner? account) (= id (:working-id projected)))
                  (get projected field))
                ;; An account switch or pending restoration must not snapshot
                ;; defaults or delete the previous owner's private record.
                (:value (storage/read! storage-id options))
                storage/missing))) options))
    (storage/schedule!)))

(r/reg-fx :blog-editor/untrack-drafts
  (fn [{:keys [id owner]}]
    (when (= owner (:owner (storage/state-value state-path)))
      (storage/flush! :navigation)
      (storage/drain!)
      (storage/untrack! id)
      (storage/untrack! (collection-id-for id)))))

(r/reg-fx :blog-editor/commit-drafts
  (fn [{:keys [owner account token]}]
    (try
      (when-not (private-owner? account) (throw (js/Error. "Sign in before saving a private draft.")))
      (storage/flush! :navigation)
      (when (false? (get (storage/drain!) @storage/*identity))
        (throw (js/Error. "The draft could not be saved. Browser storage may be full or unavailable.")))
      (r/dispatch [:blog/draft-written owner account token (.now js/Date)])
      (catch :default error (r/dispatch [:blog/draft-failed owner account token (.-message error)])))))

(r/reg-event-fx :blog/load-draft
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ owner]]
    (when (active-owner? db owner)
      (let [account (account-id db)
            previous (get-in db (conj state-path :account))
            db' (if (and previous (not= previous account)) (assoc-in db form-path nil) db)
            before (select-keys (get-in db' form-path) form-fields)
            token (str (random-uuid))
            id (working-id db')
            document-id (or (when (and (= previous account) (= id (get-in db (conj state-path :working-id))))
                             (get-in db (conj state-path :document-id)))
                            (str (random-uuid)))
            editing (select-keys (get-in db' [:state :blog :editing]) [:title :text :tags])
            restore? (or (empty-form? before) (= (dissoc before :image-assets) editing))]
        (if account
          {:db (assoc-in db' state-path {:owner owner, :account account, :token token, :status :loading, :operation :load})
           :blog-editor/read-drafts {:id id
                                     :owner owner
                                     :account account
                                     :token token
                                     :before before
                                     :restore? restore?
                                     :document-id document-id}}
          {:db (update-in db' [:state :blog-editor] dissoc :draft)})))))

(r/reg-event-fx :blog/drafts-read
  {:args [:tuple :string common/id :string [:vector :any] :map :boolean :string [:maybe schema/working] [:maybe schema/collection]]}
  (fn [{:keys [db]} [_ owner account token id before restore? document-id working collection]]
    (when (current-request? db owner account token)
      (let [entries (or (:entries collection) {})
            restore?' (and working restore? (= before (select-keys (get-in db form-path) form-fields)))
            saved-form (if (:named? working) (get-in entries [(:document-id working) :form]) (:form working))
            restore?'' (and restore?' saved-form)
            document-id' (if restore?'' (:document-id working) document-id)
            state {:owner owner
                   :account account
                   :working-id id
                   :document-id document-id'
                   :named? (boolean (contains? entries document-id'))
                   :entries entries
                   :status (if restore?'' :restored :idle)}]
        {:db (cond-> (assoc-in db state-path state) restore?'' (assoc-in form-path saved-form))
         :blog-editor/track-drafts {:id id, :owner owner, :account account}}))))

(r/reg-event-fx :blog/save-draft
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ owner]]
    (when (active-owner? db owner)
      (let [account (account-id db)
            state (get-in db state-path)
            form (canonical-form db owner)
            token (str (random-uuid))
            pending? (model/unresolved? (:text form)
                        (into {} (filter #(= owner (:owner (val %)))) (get-in db [:state :blog :image-uploads])))]
        (if (or (nil? account) (nil? (:document-id state)) (string/blank? (:title form))
                (validation/explain schema/form form))
          {:db (assoc-in db (conj state-path :error) "Enter a title before saving a named draft.")}
          {:db (assoc-in db state-path
                        (cond-> (-> state (assoc :named? true, :status :saving, :operation :save, :token token)
                                            (dissoc :error :warning))
                          pending? (assoc :warning "Pending images are not saved. Finish those uploads before leaving.")))
           :blog-editor/commit-drafts {:owner owner, :account account, :token token}})))))

(defn switch-document [db owner state form]
  (let [uploads (get-in db [:state :blog :image-uploads])
        owned (filter #(= owner (:owner %)) (vals uploads))]
    {:db (-> db (assoc-in form-path form)
                 (assoc-in state-path (dissoc state :token :error :warning))
                 (update-in [:state :blog :image-uploads] #(apply dissoc % (map :id owned))))
     :fx (mapv (fn [{:keys [id]}] [:blog-editor/settle-upload {:id id, :error "Draft changed"}]) owned)
     :dispatch [:blog/image-upload-next]}))

(r/reg-event-fx :blog/select-draft
  {:args [:tuple :string :string]}
  (fn [{:keys [db]} [_ owner id]]
    (let [state (get-in db state-path)
          entries (get-in (projection db owner) [:collection :entries])]
      (when (and (active-owner? db owner) (= (account-id db) (:account state)) (get entries id))
        (switch-document db owner (assoc state :document-id id, :named? true, :entries entries, :status :restored)
                         (get-in entries [id :form]))))))

(r/reg-event-fx :blog/new-draft
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ owner]]
    (let [state (get-in db state-path)]
      (when (and (active-owner? db owner) (= (account-id db) (:account state)) (:document-id state))
        (switch-document db owner
          (assoc state :document-id (str (random-uuid)), :named? false, :status :idle
                       :entries (get-in (projection db owner) [:collection :entries]))
          {:title "", :text "", :tags "", :image-assets {}})))))

(r/reg-event-fx :blog/draft-unmount
  {:args [:tuple :string [:vector :any]]}
  (fn [_ [_ owner id]] {:blog-editor/untrack-drafts {:id id, :owner owner}}))

(r/reg-event-db :blog/draft-written
  {:args [:tuple :string common/id :string [:int {:min 0}]]}
  (fn [db [_ owner account token saved-at]]
    (if (current-request? db owner account token)
      (update-in db state-path #(-> % (assoc :status :saved, :saved-at saved-at) (dissoc :token :error)))
      db)))

(r/reg-event-db :blog/draft-failed
  {:args [:tuple :string common/id :string :string]}
  (fn [db [_ owner account token error]]
    (if (current-request? db owner account token)
      (update-in db state-path #(-> % (assoc :status :failed, :error error) (dissoc :token)))
      db)))
