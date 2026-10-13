(ns tolgraven.modules.blog.editor-events
  (:require [tolgraven.react :as r]
            [tolgraven.media.schema :as media]
            [tolgraven.validation :as validation]
            [tolgraven.modules.blog.editor-model :as model]
            [tolgraven.supabase.runtime :as client]))

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
        #(r/dispatch [:blog/image-upload-failed id attempt %])))))

(r/reg-event-fx :blog/image-upload-enqueue
  {:args [:tuple [:vector [:map [:id :string] [:name :string] [:file :any] [:owner :string]]]
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
       :dispatch [:blog/image-upload-next]}))))

(r/reg-event-fx :blog/image-upload-retry
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ id]]
    (when (= :failed (get-in db (conj uploads-path id :status)))
      {:db (update-in db (conj uploads-path id)
                      #(-> % (assoc :status :queued) (dissoc :error :polls :job-id)))
       :dispatch [:blog/image-upload-next]})))

(r/reg-event-fx :blog/image-upload-cancel
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ id]]
    (when-let [upload (get-in db (conj uploads-path id))]
      {:db (-> db
               (update-in text-path model/cancel-upload upload)
               (update-in uploads-path dissoc id))
       :dispatch [:blog/image-upload-next]})))

(r/reg-event-fx :blog/image-upload-dispose
  {:args [:tuple :string]}
  (fn [{:keys [db]} [_ owner]]
    (let [uploads (filter #(= owner (:owner %)) (vals (get-in db uploads-path)))]
      {:db (-> db
               (update-in text-path #(reduce model/cancel-upload % uploads))
               (update-in uploads-path #(apply dissoc % (map :id uploads))))
       :dispatch [:blog/image-upload-next]})))
