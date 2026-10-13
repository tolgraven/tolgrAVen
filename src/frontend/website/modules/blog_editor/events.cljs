(ns website.modules.blog-editor.events
  (:require [website.react :as r]
            [website.media.schema :as media]
            [website.modules.blog.schema :as schema]
            [website.modules.blog-editor.upload-bridge]
            [website.validation :as validation]
            [website.modules.blog-editor.model :as model]
            [website.supabase.runtime :as client]))

(def uploads-path [:state :blog :image-uploads])
(def text-path [:state :form-field :post-blog :text])

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
        "ready" {:db (-> db
                         (update-in text-path model/complete-upload upload asset)
                         (update-in (conj uploads-path id) #(-> % (assoc :status :ready :asset asset)
                                                               (dissoc :file :error :polls))))
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
               (update-in uploads-path dissoc id))
       :blog-editor/settle-upload {:id id, :error "Image upload removed"}
       :dispatch [:blog/image-upload-next]})))

(r/reg-event-fx :blog/image-upload-dispose
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ owner]]
    (let [uploads (filter #(= owner (:owner %)) (vals (get-in db uploads-path)))]
      {:db (-> db
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
    {:dispatch-n [[:form-field [:post-blog] nil :blur]
                  [:blog/state [:editing] nil]]}))

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
