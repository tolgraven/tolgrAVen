(ns website.modules.blog-editor.events
  (:require [website.react :as r]
            [website.media.schema :as media]
            [website.modules.blog.schema :as schema]
            [website.modules.markdown-editor.events]
            [website.modules.markdown-editor.model :as model]
            [website.validation :as validation]))

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
                  (model/unresolved? (:text input)
                                     (into {} (remove #(= :comment (:kind (val %))))
                                           (get-in db [:state :blog :image-uploads]))))
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
