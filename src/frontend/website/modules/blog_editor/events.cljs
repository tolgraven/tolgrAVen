(ns website.modules.blog-editor.events
  (:require [website.react :as r]
            [website.media.schema :as media]
            [website.modules.blog.schema :as schema]
            [website.modules.blog-editor.upload-bridge]
            [website.validation :as validation]
            [website.modules.blog-editor.model :as model]
            [website.modules.blog-editor.anchors :as anchors]
            [website.supabase.runtime :as client]))

(def uploads-path [:state :blog :image-uploads])
(def text-path [:state :form-field :post-blog :text])

(r/reg-sub :blog-editor/uploads
  :<- [:blog/state [:image-uploads]]
  (fn [uploads query]
    (let [[_ owner] (or (:re-frame/query-v query) query)]
      (vec (sort-by :order (filter #(= owner (:owner %)) (vals uploads)))))))

(r/reg-event-db :blog/editor-mounted
  {:args [:tuple :string]}
  (fn [db [_ owner]] (assoc-in db [:state :blog-editor :active-owner] owner)))

(r/reg-event-fx :blog/editor-text-changed
  {:args [:cat :string :string [:maybe [:vector anchors/change-schema]] [:maybe anchors/positions-schema] [:? :?string]]}
  (fn [{:keys [db]} [_ owner text changes positions document-id]]
    (when (and (= owner (get-in db [:state :blog-editor :active-owner]))
               (= document-id (get-in db [:state :blog-editor :draft :document-id])))
    (let [uploads (get-in db uploads-path)
          changes' (or changes [(anchors/text-change (get-in db text-path) text)])
          owned (into {} (filter #(= owner (:owner (val %)))) uploads)
          mapped (if positions
                   (into {} (keep (fn [[id upload]]
                                    (if (or (:chip-dismissed? upload) (not (:anchor-attached? upload))) [id upload]
                                        (when-let [position (get positions id)]
                                          [id (assoc upload :position position)])))) owned)
                   (model/map-uploads owned changes'))
          removed (remove #(contains? mapped %) (keys owned))]
      (cond-> {:db (-> db (assoc-in text-path text)
                   (assoc-in uploads-path (merge (apply dissoc uploads (keys owned)) mapped)))
               :fx (mapv (fn [id] [:blog-editor/settle-upload {:id id, :error "Upload insertion removed"}]) removed)}
        (seq removed) (assoc :dispatch [:blog/image-upload-next]))))))

(r/reg-event-db :blog/editor-mode
  {:args [:tuple :string :boolean]}
  (fn [db [_ owner rich?]]
    (update-in db uploads-path
               (fn [uploads]
                 (into {} (keep (fn [[id upload]]
                                  (when-not (and (not rich?) (= owner (:owner upload)) (:cancelled? upload))
                                    [id (cond-> upload (= owner (:owner upload)) (assoc :sdk? rich?))]))) uploads)))))

(r/reg-event-db :blog/editor-anchors
  {:args [:tuple :string anchors/positions-schema]}
  (fn [db [_ owner positions]]
    (update-in db uploads-path
               (fn [uploads]
                 (into {} (map (fn [[id upload]]
                                 [id (if (and (= owner (:owner upload)) (contains? positions id))
                                       (assoc upload :position (get positions id), :anchor-attached? true) upload)])) uploads)))))

(r/reg-event-db :blog/image-chip-exited
  {:args [:tuple :string :string [:int {:min 1}] [:int {:min 0}]]}
  (fn [db [_ owner id attempt generation]]
    (let [upload (get-in db (conj uploads-path id))]
      (if (and (= owner (:owner upload)) (= attempt (:attempt upload))
               (= generation (or (:preview-attempt upload) 0)) (:preview-ready? upload))
        (assoc-in db (conj uploads-path id :chip-dismissed?) true)
        db))))

(r/reg-fx :blog-editor/native-image-insert
  (fn [{:keys [insert! owner id attempt src]}]
    (insert! #(r/dispatch [:blog/image-upload-inserted owner id attempt src %]))))

(r/reg-event-fx :blog/image-upload-insert
  {:args [:tuple :string :string [:int {:min 1}] :string fn?]}
  (fn [{:keys [db]} [_ owner id attempt src insert!]]
    (let [upload (get-in db (conj uploads-path id))]
      (when (and (= owner (:owner upload)) (= attempt (:attempt upload))
                 (= :ready (:status upload)) (:sdk? upload)
                 (not (:image-inserting? upload)) (not (:image-inserted? upload))
                 (= src (get-in upload [:asset :src])))
        {:db (assoc-in db (conj uploads-path id :image-inserting?) true)
         :blog-editor/native-image-insert {:insert! insert!, :owner owner, :id id, :attempt attempt, :src src}}))))

(r/reg-event-fx :blog/image-upload-inserted
  {:args [:tuple :string :string [:int {:min 1}] :string :boolean]}
  (fn [{:keys [db]} [_ owner id attempt src success?]]
    (let [upload (get-in db (conj uploads-path id))]
      (when (and (= owner (:owner upload)) (= attempt (:attempt upload))
                 (:image-inserting? upload) (= src (get-in upload [:asset :src])))
        (if success?
          {:db (update-in db (conj uploads-path id) #(-> % (assoc :image-inserted? true) (dissoc :image-inserting?)))}
          {:dispatch [:blog/image-upload-cancel id]})))))

(defn accept-response! [id attempt result]
  (r/dispatch [:blog/image-upload-received id attempt result]))

(r/reg-event-fx :blog/image-upload-received
  {:args [:tuple :string [:int {:min 1}] :any]}
  (fn [_ [_ id attempt result]]
    {:dispatch (if (validation/explain media/upload-response result)
                 [:blog/image-upload-failed id attempt {:message "The upload returned an invalid response. Retry or remove it."}]
                 [:blog/image-upload-response id attempt result])}))

(r/reg-fx :blog/image-upload
  (fn [{:keys [id file attempt]}]
    (let [body (doto (js/FormData.) (.append "file" file))]
      (client/authenticated-request!
        :post "/api/supabase/media" body
        #(accept-response! id attempt %)
        #(r/dispatch [:blog/image-upload-failed id attempt %])
        {:on-upload-progress #(r/dispatch [:blog/image-upload-progress id attempt %])}))))

(r/reg-event-db :blog/image-upload-progress
  {:args [:tuple :string [:int {:min 1}] :any]}
  (fn [db [_ id attempt progress]]
    (let [upload (get-in db (conj uploads-path id))]
      (if (and (= :uploading (:status upload)) (= attempt (:attempt upload))
               (nil? (validation/explain model/progress-schema progress))
               (>= (:loaded progress) (get-in upload [:progress :loaded] 0)))
        (assoc-in db (conj uploads-path id :progress) progress)
        db))))

(r/reg-event-fx :blog/image-upload-enqueue
  {:args [:tuple [:vector [:map [:id :string] [:name :string] [:file :any] [:owner :string] [:?sdk? :boolean]]]
                 [:int {:min 0}] [:int {:min 0}]]}
  (fn [{:keys [db]} [_ files start end]]
    (let [{:keys [text uploads]} (model/insert-files (get-in db text-path) start end
                                                  (get-in db uploads-path) files)]
      {:db (-> db (assoc-in text-path text) (assoc-in uploads-path uploads))
       :dispatch [:blog/image-upload-next]})))

(r/reg-event-fx :blog/image-upload-next
  {:args [:tuple]}
  (fn [{:keys [db]} _]
    (when-let [{:keys [id file attempt]} (model/next-upload (get-in db uploads-path))]
      (let [attempt' (inc (or attempt 0))]
        {:db (update-in db (conj uploads-path id) assoc :status :uploading :attempt attempt')
         :blog/image-upload {:id id, :file file, :attempt attempt'}}))))

(r/reg-event-fx :blog/image-upload-response
  {:args [:tuple :string [:int {:min 1}] media/upload-response]}
  (fn [{:keys [db]} [_ id attempt {:keys [status asset error] :as result}]]
    (let [upload (get-in db (conj uploads-path id))]
      (when (and upload (= attempt (:attempt upload))
                 (#{:uploading :processing} (:status upload)))
      (case status
        "ready" {:db (let [text (get-in db text-path)
                           text' (model/complete-upload text upload asset)
                           inserted (- (count text') (count (or text "")))
                           uploads (get-in db uploads-path)
                           uploads' (if (:sdk? upload) uploads
                                        (into {} (map (fn [[key item]]
                                                        [key (if (and (not= key id) (>= (:position item) (:position upload)))
                                                               (update item :position + inserted) item)])) uploads))]
                       (-> db (assoc-in text-path text') (assoc-in uploads-path uploads')
                         (update-in (conj uploads-path id) #(-> % (assoc :status :ready :asset asset
                                                                       :image-inserted? (not (:sdk? upload)))
                                                               (dissoc :file :error :polls)))))
                 :blog-editor/settle-upload {:id id, :src (:src asset)}
                 :dispatch [:blog/image-upload-next]}
        "failed" {:dispatch [:blog/image-upload-failed id attempt {:message error}]}
        ;; The response owns its durable job id; client polling owns no timers
        ;; outside re-frame. Cancelling this draft makes later polls no-ops.
        (let [polls (inc (or (:polls upload) 0))]
          (if (< polls 360)
            {:db (update-in db (conj uploads-path id)
                            assoc :status :processing :job-id (:id result) :polls polls)
             :dispatch-later [{:ms 500, :dispatch [:blog/image-upload-poll id attempt]}]}
            {:dispatch [:blog/image-upload-failed id attempt
                        {:message "Processing is taking longer than expected. Retry to check again."}]})))))))

(r/reg-event-fx :blog/image-upload-poll
  {:args [:tuple :string [:int {:min 1}]]}
  (fn [{:keys [db]} [_ id attempt]]
    (let [{:keys [status job-id] :as upload} (get-in db (conj uploads-path id))]
      (when (and (= :processing status) (= attempt (:attempt upload)))
        {:supabase/request {:method :get
                            :uri (str "/api/supabase/media/" (js/encodeURIComponent job-id))
                            :on-success [:blog/image-upload-received id attempt]
                            :on-error [:blog/image-upload-failed id attempt]}}))))

(r/reg-event-fx :blog/image-upload-failed
  {:args [:tuple :string [:int {:min 1}] :map]}
  (fn [{:keys [db]} [_ id attempt error]]
    (let [upload (get-in db (conj uploads-path id))]
      (when (and upload (= attempt (:attempt upload))
                 (#{:uploading :processing} (:status upload)))
      {:db (update-in db (conj uploads-path id)
                      assoc :status :failed
                      :error (or (:message error) "Unable to upload this image. Retry or remove it."))
       :blog-editor/settle-upload {:id id, :error (or (:message error) "Unable to upload image")}
       :dispatch [:blog/image-upload-next]}))))

(def preview-event-args [:tuple :string [:int {:min 1}] :string :string [:int {:min 0}]])

(defn current-preview? [upload attempt src owner generation]
  (and (= :ready (:status upload)) (= attempt (:attempt upload))
       (= src (get-in upload [:asset :src])) (= owner (:owner upload))
       (= generation (or (:preview-attempt upload) 0))))

(r/reg-event-db :blog/image-preview-ready
  {:args preview-event-args}
  (fn [db [_ id attempt src owner generation]]
    (let [upload (get-in db (conj uploads-path id))]
      (if (current-preview? upload attempt src owner generation)
        (update-in db (conj uploads-path id) #(-> % (assoc :preview-ready? true) (dissoc :preview-error)))
        db))))

(r/reg-event-db :blog/image-preview-failed
  {:args preview-event-args}
  (fn [db [_ id attempt src owner generation]]
    (let [upload (get-in db (conj uploads-path id))]
      (if (current-preview? upload attempt src owner generation)
        (update-in db (conj uploads-path id) assoc :preview-ready? false
                   :preview-error "The image uploaded, but its preview could not load. Retry the preview or remove this notice.")
        db))))

(r/reg-event-db :blog/image-preview-retry
  {:args [:tuple :string]}
  (fn [db [_ id]]
    (if-let [upload (get-in db (conj uploads-path id))]
      (if (and (= :ready (:status upload)) (:preview-error upload))
        (update-in db (conj uploads-path id) #(-> % (dissoc :preview-error)
                                                  (assoc :preview-ready? false)
                                                  (update :preview-attempt (fnil inc 0))))
        db)
      db)))

(r/reg-event-fx :blog/image-upload-retry
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ id]]
    (when (= :failed (get-in db (conj uploads-path id :status)))
      {:db (update-in db (conj uploads-path id)
                      #(-> % (assoc :status :queued) (dissoc :error :polls :job-id :progress :preview-ready? :preview-error :preview-attempt)))
       :dispatch [:blog/image-upload-next]})))

(r/reg-event-fx :blog/image-upload-cancel
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ id]]
    (when-let [upload (get-in db (conj uploads-path id))]
      {:db (-> db
               (update-in text-path model/cancel-upload upload)
               (update-in uploads-path
                          #(if (and (:sdk? upload) (not (:image-inserted? upload)))
                             ;; The native document owner acknowledges removal;
                             ;; absent context during its initial mount is not cancel.
                             (update % id (fn [item] (-> item (assoc :cancelled? true, :status :failed) (dissoc :file))))
                             (dissoc % id))))
       :blog-editor/settle-upload {:id id, :error "Image upload removed"}
       :dispatch [:blog/image-upload-next]})))

(r/reg-event-fx :blog/image-anchor-removed
  {:args [:tuple :string :string]}
  (fn [{:keys [db]} [_ owner id]]
    (let [upload (get-in db (conj uploads-path id))]
      (when (= owner (:owner upload))
        {:db (update-in db uploads-path dissoc id)
         :blog-editor/settle-upload {:id id, :error "Image insertion removed"}
         :dispatch [:blog/image-upload-next]}))))

(r/reg-event-fx :blog/image-upload-dispose
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ owner]]
    (let [uploads (filter #(= owner (:owner %)) (vals (get-in db uploads-path)))]
      {:db (-> db
               (cond-> (= owner (get-in db [:state :blog-editor :active-owner]))
                 (update-in [:state :blog-editor] dissoc :active-owner))
               (update-in text-path #(reduce model/cancel-upload % uploads))
               (update-in uploads-path #(apply dissoc % (map :id uploads))))
       :fx (mapv (fn [{:keys [id]}] [:blog-editor/settle-upload {:id id, :error "Editor closed"}]) uploads)
       :dispatch [:blog/image-upload-next]})))

(r/reg-event-fx :blog/init-posting
  {:args (get schema/event-args :blog/init-posting)}
  (fn [_ _] {:dispatch [:user/close-ui]}))

(r/reg-event-fx :blog/cancel-edit
  {:args (get schema/event-args :blog/cancel-edit)}
  (fn [_ _]
    ;; Route controllers call this before releasing the authoring page. Drain
    ;; the accepted form before queued clears make its projection unavailable.
    {:fx [[:component-storage/flush :navigation]
          [:dispatch-n [[:form-field [:post-blog] nil :blur]
                        [:blog/state [:editing] nil]]]]}))

(r/reg-event-fx :blog/submit
  {:args (get schema/event-args :blog/submit)}
  (fn [{:keys [db]} [_ input editing]]
    (when-not (or (get-in db [:state :supabase-writes :post])
                  (model/unresolved? (:text input) (get-in db [:state :blog :image-uploads])))
      {:db (assoc-in db [:state :supabase-writes :post] true)
       :supabase/request {:method :post :uri "/api/supabase/posts"
                          :data (cond-> (select-keys input [:title :text :tags])
                                  editing (assoc :post-id (:id editing)))
                          :on-success [:blog/post-saved input]
                          :on-error [:blog/write-failed :post]}})))

(r/reg-event-fx :blog/post-saved
  {:args (get schema/event-args :blog/post-saved)}
  (fn [{:keys [db]} [_ submitted _]]
    {:db (update-in db [:state :supabase-writes] dissoc :post)
     :dispatch-n (cond-> [[:common/navigate! :blog] [:blog/state [:editing] nil]]
                   (= (select-keys submitted [:title :text :tags])
                      (select-keys (get-in db [:state :form-field :post-blog]) [:title :text :tags]))
                   (conj [:form-field [:post-blog] nil :blur]))}))


(r/reg-event-fx :blog-editor/library-load
  {:args [:tuple [:int {:min 0}]]}
  (fn [{:keys [db]} [_ offset]]
    (when-not (get-in db [:state :blog-editor :library :pending?])
      {:db (assoc-in db [:state :blog-editor :library :pending?] true)
       :supabase/request {:method :get
                          :uri (str "/api/supabase/media?offset=" offset "&limit=24")
                          :on-success [:blog-editor/library-loaded offset]
                          :on-error [:blog-editor/library-failed]}})))

(r/reg-event-fx :blog-editor/library-loaded
  {:args [:tuple [:int {:min 0}] :any]}
  (fn [{:keys [db]} [_ offset result]]
    (if (validation/explain media/library result)
      {:dispatch [:blog-editor/library-failed {:message "Image library returned invalid metadata"}]}
      {:db (update-in db [:state :blog-editor :library]
                      (fn [library]
                        {:pending? false
                         :assets (vec (vals (into {} (map (juxt :id identity))
                                                   (concat (when (pos? offset) (:assets library)) (:assets result)))))
                         :next-offset (:next-offset result)}))})))

(r/reg-event-db :blog-editor/library-failed
  {:args [:tuple :map]}
  (fn [db [_ error]]
    (update-in db [:state :blog-editor :library] assoc :pending? false
               :error (or (:message error) "Unable to load images. Retry."))))
