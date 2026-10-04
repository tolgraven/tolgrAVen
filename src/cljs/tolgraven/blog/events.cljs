(ns tolgraven.blog.events
  (:require
    [re-frame.core :as rf]
    [re-frame.std-interceptors :refer [path]]
    [tolgraven.blog.model :as model]
    [tolgraven.interceptors :refer [debug]]))


(rf/reg-event-fx :blog/init
  (fn [{:keys [db]} _]
    (when-not (get-in db [:state :booted :blog])
      {:dispatch-n [[:ls/get-path [:blog] [:state :blog]]
                    [:blog/set-posts-per-page 3]
                    [:booted :blog]]})))

(rf/reg-event-fx :blog/init-posting
  (fn [_ _] {:dispatch [:user/close-ui]}))

(rf/reg-event-fx :blog/edit-post
  (fn [_ [_ post]]
    {:dispatch-n [[:form-field [:post-blog] post :blur]
                  [:blog/state [:editing] post]
                  [:common/navigate! :new-post]]}))

(rf/reg-event-fx :blog/cancel-edit
  (fn [_ _]
    {:dispatch-n [[:form-field [:post-blog] nil :blur]
                  [:blog/state [:editing] nil]]}))

(rf/reg-event-db :blog/state
  [(path [:state :blog])]
  (fn [blog [_ state-path value]] (assoc-in blog state-path value)))

(rf/reg-event-db :blog/set-posts-per-page
  [(path [:options :blog])]
  (fn [options [_ size]] (assoc options :posts-per-page (model/page-size size))))

(rf/reg-event-fx :blog/nav-action
  (fn [{:keys [db]} [_ action]]
    (let [stored (get-in db [:state :blog :page])
          index (if (and (int? stored) (<= 0 stored)) stored 0)
          number (case action :prev index :next (+ index 2) action)]
      {:dispatch [:blog/nav-page number]})))

(rf/reg-event-db :blog/nav-page
  [(path [:state :blog :page])]
  (fn [_ [_ number]] (model/page-index number)))

(rf/reg-event-fx :blog/submit
  (fn [{:keys [db]} [_ input editing]]
    (when-not (get-in db [:state :supabase-writes :post])
      {:db (assoc-in db [:state :supabase-writes :post] true)
       :supabase/request {:method :post :uri "/api/supabase/posts"
                          :data (cond-> (select-keys input [:title :text :tags])
                                  editing (assoc :post-id (:id editing)))
                          :on-success [:blog/post-saved input]
                          :on-error [:blog/write-failed :post]}})))

(rf/reg-event-fx :blog/post-saved
  (fn [{:keys [db]} [_ submitted _]]
    {:db (update-in db [:state :supabase-writes] dissoc :post)
     :dispatch-n (cond-> [[:common/navigate! :blog] [:blog/state [:editing] nil]]
                   (= (select-keys submitted [:title :text :tags])
                      (select-keys (get-in db [:state :form-field :post-blog]) [:title :text :tags]))
                   (conj [:form-field [:post-blog] nil :blur]))}))

(rf/reg-event-fx :blog/edit-comment
  (fn [_ [_ path comment]]
    (let [parent-path (vec (butlast path))]
      {:dispatch-n [[:form-field [:write-comment parent-path] (select-keys comment [:text :title]) :blur]
                    [:blog/state [:editing-comment parent-path] comment]
                    [:blog/adding-comment parent-path true]]})))

(rf/reg-event-fx :blog/cancel-comment
  (fn [_ [_ path]]
    {:dispatch-n [[:blog/adding-comment path false]
                  [:blog/state [:editing-comment path] nil]
                  [:form-field [:write-comment path] nil :blur]]}))

(rf/reg-event-fx :blog/comment-submit [debug]
  (fn [{:keys [db]} [_ path input editing]]
    (when-not (get-in db [:state :supabase-writes [:comment path]])
      {:db (assoc-in db [:state :supabase-writes [:comment path]] true)
       :supabase/request
       {:method (if editing :put :post) :uri "/api/supabase/comments"
        :data (merge (select-keys input [:text :title])
                     (if editing {:comment-id (str (:id editing))}
                         {:post-id (first path) :parent-id (when (< 1 (count path)) (str (last path)))}))
        :on-success [:blog/comment-saved path input]
        :on-error [:blog/write-failed [:comment path]]}})))

(rf/reg-event-fx :blog/comment-saved
  (fn [{:keys [db]} [_ path submitted _]]
    {:db (update-in db [:state :supabase-writes] dissoc [:comment path])
     :dispatch-n (cond-> [[:supabase/profile-fetch]
                         [:blog/expand-comment-thread path true]]
                   (= submitted (get-in db [:state :form-field :write-comment path]))
                   (into [[:form-field [:write-comment path] nil :blur]
                          [:blog/state [:editing-comment path] nil]
                          [:blog/adding-comment path nil]]))}))

(rf/reg-event-fx :blog/write-failed
  (fn [{:keys [db]} [_ operation error]]
    {:db (update-in db [:state :supabase-writes] dissoc operation)
     :dispatch [:supabase/write-error error]}))

(rf/reg-event-fx :blog/comment-vote [debug]
  (fn [{:keys [db]} [_ _ _ path vote]]
    (let [id (str (last path))
          current (get-in db [:state :active-user :comment-votes (keyword id)] 0)
          requested (case vote :up 1 :down -1)]
      (when-not (get-in db [:state :supabase-writes [:vote id]])
        {:db (assoc-in db [:state :supabase-writes [:vote id]] true)
         :supabase/request {:method :post :uri "/api/supabase/votes"
                            :data {:comment-id id :vote (if (= current requested) "none" (name vote))}
                            :on-success [:blog/vote-saved id]
                            :on-error [:blog/write-failed [:vote id]]}}))))

(rf/reg-event-fx :blog/vote-saved
  (fn [{:keys [db]} [_ id result]]
    {:db (-> db
             (update-in [:state :supabase-writes] dissoc [:vote id])
             (assoc-in [:state :active-user :comment-votes (keyword id)] (:vote result)))
     :dispatch-n [[:supabase/profile-fetch]]}))

(defn- persist-comment-state [db key comment-path value]
  {:db (assoc-in db [:state :blog key comment-path] value)
   :dispatch [:ls/store-val [:blog key comment-path] value]})

(rf/reg-event-fx :blog/expand-comment-thread
  (fn [{:keys [db]} [_ comment-path expanded?]]
    (persist-comment-state db :comment-thread-expanded comment-path expanded?)))

(rf/reg-event-fx :blog/adding-comment
  (fn [{:keys [db]} [_ parent-path adding?]]
    (persist-comment-state db :adding-comment parent-path adding?)))
