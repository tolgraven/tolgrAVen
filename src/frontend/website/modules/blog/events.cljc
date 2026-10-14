(ns website.modules.blog.events
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  #?(:cljs (:require #?(:feature/blog-comments [website.modules.blog.comments :as comments])
    [website.react :as r]

    [website.modules.blog.model :as model]
    [website.modules.blog.schema :as schema]
    [website.component.storage :as storage]
    [website.page :as page]
    [website.supabase.scoped :as scoped]
    [website.util :as util]
    [website.interceptors :refer [debug]] )))

#?(:cljs
   (do
(r/reg-event-fx :blog/init
  {:args (get schema/event-args :blog/init)}
  (fn [{:keys [db]} _]
    (when-not (get-in db [:state :booted :blog])
      {:dispatch-n [[:blog/set-posts-per-page 3]
                    [:booted :blog]]})))

(r/reg-event-fx :blog/edit-post
  {:args (get schema/event-args :blog/edit-post)}
  (fn [_ [_ post]]
    {:dispatch-n [[:form-field [:post-blog] post :blur]
                  [:blog/state [:editing] post]
                  [:common/navigate! :new-post]]}))

(r/reg-event-db :blog/state
  {:args (get schema/event-args :blog/state)}
  [(r/path [:state :blog])]
  (fn [blog [_ state-path value]] (assoc-in blog state-path value)))

(r/reg-event-db :blog/set-posts-per-page
  {:args (get schema/event-args :blog/set-posts-per-page)}
  [(r/path [:options :blog])]
  (fn [options [_ size]] (assoc options :posts-per-page (model/page-size size))))

(r/reg-event-fx :blog/nav-action
  {:args (get schema/event-args :blog/nav-action)}
  (fn [{:keys [db]} [_ action]]
    (let [stored (get-in db [:state :blog :page])
          index (if (and (int? stored) (<= 0 stored)) stored 0)
          number (case action :prev index :next (+ index 2) action)]
      {:dispatch [:blog/nav-page number]})))

(r/reg-event-db :blog/nav-page
  {:args (get schema/event-args :blog/nav-page)}
  [(r/path [:state :blog :page])]
  (fn [_ [_ number]] (model/page-index number)))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/edit-comment
  {:args (get schema/event-args :blog/edit-comment)}
  (fn [_ [_ path comment]]
    (let [parent-path (vec (butlast path))]
      {:dispatch-n [[:form-field [:write-comment parent-path] (select-keys comment [:text :title]) :blur]
                    [:blog/state [:editing-comment parent-path] comment]
                    [:blog/adding-comment parent-path true]]}))))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/cancel-comment
  {:args (get schema/event-args :blog/cancel-comment)}
  (fn [_ [_ path]]
    {:dispatch-n [[:blog/adding-comment path false]
                  [:blog/state [:editing-comment path] nil]
                  [:form-field [:write-comment path] nil :blur]]})))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/comment-submit
  {:args (get schema/event-args :blog/comment-submit)} [debug]
  (fn [{:keys [db]} [_ path input editing]]
    (when-not (get-in db [:state :supabase-writes [:comment path]])
      {:db (assoc-in db [:state :supabase-writes [:comment path]] true)
       :supabase/request
       {:method (if editing :put :post) :uri "/api/supabase/comments"
        :data (merge (select-keys input [:text :title])
                     (if editing {:comment-id (str (:id editing))}
                         {:post-id (first path) :parent-id (when (< 1 (count path)) (str (last path)))}))
        :on-success [:blog/comment-saved path input]
        :on-error [:blog/write-failed [:comment path]]}}))))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/comment-saved
  {:args (get schema/event-args :blog/comment-saved)}
  (fn [{:keys [db]} [_ path submitted _]]
    {:db (update-in db [:state :supabase-writes] dissoc [:comment path])
     :dispatch-n (cond-> [[:supabase/profile-fetch]
                         [:blog/expand-comment-thread path true]]
                   (= submitted (get-in db [:state :form-field :write-comment path]))
                   (into [[:form-field [:write-comment path] nil :blur]
                          [:blog/state [:editing-comment path] nil]
                          [:blog/adding-comment path nil]]))})))

(r/reg-event-fx :blog/write-failed
  {:args (get schema/event-args :blog/write-failed)}
  (fn [{:keys [db]} [_ operation error]]
    {:db (update-in db [:state :supabase-writes] dissoc operation)
     :dispatch [:supabase/write-error error]}))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/comment-vote
  {:args (get schema/event-args :blog/comment-vote)} [debug]
  (fn [{:keys [db]} [_ _ _ path vote]]
    (let [id (str (last path))
          current (get-in db [:state :active-user :comment-votes (keyword id)] 0)
          requested (case vote :up 1 :down -1)]
      (when-not (get-in db [:state :supabase-writes [:vote id]])
        {:db (assoc-in db [:state :supabase-writes [:vote id]] true)
         :supabase/request {:method :post :uri "/api/supabase/votes"
                            :data {:comment-id id :vote (if (= current requested) "none" (name vote))}
                            :on-success [:blog/vote-saved id]
                            :on-error [:blog/write-failed [:vote id]]}})))))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/vote-saved
  {:args (get schema/event-args :blog/vote-saved)}
  (fn [{:keys [db]} [_ id result]]
    {:db (-> db
             (update-in [:state :supabase-writes] dissoc [:vote id])
             (assoc-in [:state :active-user :comment-votes (keyword id)] (:vote result)))
     :dispatch-n [[:supabase/profile-fetch]]})))

(r/reg-fx :blog/cache-state (fn [_] (storage/schedule!)))
(r/reg-event-fx :blog/cache-state-changed
  {:args (get schema/event-args :blog/cache-state-changed)} (fn [_ _] {:blog/cache-state true}))

(when-enabled :blog :comments
  (defn- persist-comment-state [db key comment-path value]
  {:db (-> db
           (assoc-in [:state :blog key comment-path] value)
           (update-in [:state :blog :restore-edits] (fnil conj #{}) [key comment-path]))
   :dispatch [:blog/cache-state-changed]}))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/expand-comment-thread
  {:args (get schema/event-args :blog/expand-comment-thread)}
  (fn [{:keys [db]} [_ comment-path expanded?]]
    (persist-comment-state db :comment-thread-expanded comment-path expanded?))))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/navigate-comment
  {:args [:tuple comments/comment-id]}
  (fn [{:keys [db]} [_ id]]
    (let [selection (page/selection (:common/route db))
          key (scoped/query-key (comments/comment-query id (:post-id selection)))
          row (some-> (get-in db [:store :scoped key]) util/normalize-store-result vals first)
          expanded (get-in db [:state :blog :comment-thread-expanded])
          expanded' (when row
                      (if (:standalone-comment? selection)
                        (assoc expanded [(:parent-post row) (:id row)] true)
                        (comments/focus-expansion expanded row)))]
      (when (and (= id (:comment-id selection)) row
                 (not= {:path (get-in db [:common/route :path]), :scroll? false}
                       (get-in db [:state :scroll :component-focus])))
        (cond-> {:dispatch-n [[:scroll/component-target
                              (if (:standalone-comment? selection) "standalone-comment" (str "blog-comment-" id))
                              (get-in db [:common/route :path])]
                             (when (not= expanded expanded') [:blog/cache-state-changed])]}
          (not= expanded expanded')
          (assoc :db (-> db
                 (assoc-in [:state :blog :comment-thread-expanded] expanded')
                 (update-in [:state :blog :restore-edits] (fnil into #{})
                            (mapv #(vector :comment-thread-expanded %)
                                  (if (:standalone-comment? selection)
                                    [[(:parent-post row) (:id row)]]
                                    (comments/ancestor-paths row))))))))))))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/adding-comment
  {:args (get schema/event-args :blog/adding-comment)}
  (fn [{:keys [db]} [_ parent-path adding?]]
    (persist-comment-state db :adding-comment parent-path adding?))))

(when-enabled :blog :comments
  (r/reg-event-fx :blog/load-more-comments
  {:args (get schema/event-args :blog/load-more-comments)}
  (fn [{:keys [db]} [_ id]]
    {:db (-> db
             (update-in [:state :blog :comment-limit id] (fnil + comments/page-size) comments/page-size)
             (update-in [:state :blog :restore-edits] (fnil conj #{}) [:comment-limit id]))
     :dispatch [:blog/cache-state-changed]})))
))
