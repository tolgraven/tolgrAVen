(ns tolgraven.blog.events
  (:require
    [re-frame.core :as rf]
    [re-frame.std-interceptors :refer [path]]
    ; [day8.re-frame.tracing :refer-macros [fn-traced]]
    [clojure.edn :as edn]
    [clojure.walk :as walk]
    [clojure.string :as string]
    [ajax.core :as ajax]
    [tolgraven.interceptors :as inter :refer [debug]]
    [tolgraven.util :as util]))


(rf/reg-event-fx :blog/init []
  (fn [{:keys [db]} _]
    (when-not (get-in db [:state :booted :blog])
     {:dispatch-n [[:<-store [:blog-posts]    [:blog/set-content :posts]]
                   [:<-store [:blog-comments] [:blog/set-content :comments]]
                   [:ls/get-path [:blog] [:state :blog]] ; get state of thangs
                   [:booted :blog]]}))) ; and then kill for main etc... but better if tag pages according to how they should modify css]}))

(rf/reg-event-fx :blog/init-posting []
  (fn [{:keys [db]} _]
    {:dispatch-n [[:user/close-ui]]})) ; and then kill for main etc... but better if tag pages according to how they should modify css]}))

(rf/reg-event-fx :blog/edit-post []
  (fn [{:keys [db]} [_ post]] ; seems gross somehow, passing full data from view. but that's where we have easy access to it so... any case would just be pass id, ask to fetch rest of contents somewhere, whe
    {:dispatch-n [[:form-field [:post-blog] post] ;well only text tags id but who's counting
                  [:blog/state [:editing] post]
                  [:common/navigate! :post-blog]]}))

(rf/reg-event-fx :blog/set-content []
 (fn [{:keys [db]} [_ category response]]
   {:db (assoc-in db [:blog category] response)}))

(rf/reg-event-db :blog/state [;debug
                              (path [:state :blog])]
 (fn [blog [_ path v]]
   (assoc-in blog path v)))

(rf/reg-event-db :blog/set-posts-per-page
  [(path [:options :blog])]
 (fn [blog [_ n]]
   (assoc blog :posts-per-page n)))

(rf/reg-event-fx :blog/nav-action
  [(path [:state :blog :page])]
 (fn [{:keys [db]} [_ nav]]
   (let [nr (case nav
              :prev (dec (inc db)) :next (inc (inc db)) ;just to clarify we're matching the offset version..
              nav)]
     {:dispatch [:blog/nav-page nr]}))) ;not very clean but would get messy otherwise..

(rf/reg-event-fx :blog/nav-page ; TODO should also (deferred) fetch content for next/prev/last and any by id directly clickable pages
  [(path [:state :blog :page])]
 (fn [{:keys [db]} [_ nr]]
   {:db (dec (js/parseInt nr))})) ;problem tho, shouldn't try when back-nav etc...


(rf/reg-event-fx :blog/fetch-navigatable-pages
                 [(path [:state :blog :navigatable-pages])]
  (fn [{:keys [db]} [_ _]]
    {:dispatch [:blog/fetch-pages db]}))

(rf/reg-event-fx :blog/fetch-pages
  (fn [{:keys [db]} [_ page-indexes]]
    {:firestore/batch-get?? {}}))

(rf/reg-event-fx :blog/fetch-posts
  (fn [{:keys [db]} [_ indexes]]))

(rf/reg-event-fx :blog/submit
 (fn [_ [_ input editing]]
   {:dispatch-n [(if editing
                   [:blog/post (merge editing input)] ;input comes after cause will have changed
                   [:blog/post-new input])
                 [:form-field [:post-blog] nil]
                 [:blog/state [:editing] nil]
                 [:blog/nav-page 1]]})) ;or whatever. also applies (even more!) to comment-ui

(rf/reg-event-fx :blog/post
 (fn [{:keys [db]} [_ post]]
   {:db (assoc-in db [:blog :posts (:id post)] post)
      :dispatch-n [[:store-> [:blog-posts (str (:id post))] post]
                   [:store-> [:users (-> post :user :id)]
                             {:blog-posts (:id post)}
                             [:blog-posts]] ]}))

(rf/reg-event-fx :blog/post-new [inter/persist-id-counters
                                 (rf/inject-cofx :now)
                                 (rf/inject-cofx :gen-id [:blog])]
 (fn [{:keys [db now id]} [_ post]]
   (let [id (-> id :id :blog)
         post (assoc post
                     :ts now
                     :id id
                     :user (-> post :user :id)
                     :permalink (-> (:title post)
                                    (str "-" id)
                                    (string/replace " " "-")))]
     {:dispatch [:blog/post post]})))

(rf/reg-event-fx :test-fetch [debug]
 (fn [{:keys [db]} [_ user-id]]
   {:firestore/get {:path-collection [:blog-comments]
                 :where [[:user :== user-id] ]
                 :limit 100
                 :order-by [[:id :desc]]
                 ; :start-at ["CA" 1000]
                 :doc-changes false
                 :on-success [:test-store]}}))

(rf/reg-event-fx :test-store [debug]
 (fn [{:keys [db]} [_ result]]
   (println result)
   {:db (assoc db :fire-result result)}))

; (rf/dispatch [:test-fetch (:id @(rf/subscribe [:user/active-user]))])


(defn paths-for "Would want a path builder that for given input returns both app-db and firestore paths. If they must differ ugh"
  [inputs])

(defn- assemble-path "Get db path from path pieces"
  [base path & further]
  (let [path (reduce (fn [p id]
                       (into p [:comments (keyword (str id))])) ;needs to be keyword tho blog uses num keys because of keywordize...
                     base path)]
    (if further ; dont want last :comments, instead something else
      (concat path further)
      path)))

; for perfect parity need
; like thing is firestore must have doc, but on app-db side that's
; no diff anyways.
; assoc-in db [:blog-posts 1] thing
; vs
; update-in db [:blog-posts] merge {1 thing}

(rf/reg-event-fx :blog/comment-submit [debug]
 (fn [{:keys [db]} [_ path input editing]]
   {:dispatch-n [(if editing
                   [:blog/comment path (merge editing input)]
                   [:blog/comment-new path input])
                 [:form-field [:write-comment path] nil]
                 [:blog/state [:editing-comment path] nil]]}))

(rf/reg-event-fx :blog/comment [debug]
 (fn [{:keys [db]} [_ path comment]]
   (let [id (:id comment)
         user-id (-> comment :user keyword)
         full-path (assemble-path [:blog :posts (keyword (str (first path)))]
                                  (concat (rest path) [id]))
         user-comments (concat (get-in db [:fb/users user-id :comments])
                               [id])] ; append to list of user comment ids
       {:db (-> db
                (assoc-in full-path comment) ;XXX should only store id
                (assoc-in [:blog :comments id] comment) ;main comments store, should it be (keyword id)?
                (update-in [:fb/users user-id] merge
                           {:comments user-comments
                            :comment-count (count user-comments)})) ; or just, inc it..
        :dispatch-n ; i mean (better) alternative is just update db and do a dispatch to sync. need perfect path parity tho
        [[:store->  ; store comment within blog-post
          [:blog-posts (str (first path))]
          (assoc-in {} (assemble-path []
                                      (concat (rest path) [id]))
                    comment) ;XXX should only store id
          [:comments]]
         [:store-> [:blog-comments (str id)] comment] ; store comment flat
         [:store->  ; update user with comment-id
          [:users user-id]
          {:comments user-comments
           :comment-count (count user-comments)}
          [:comments]]]})))

(rf/reg-event-fx :blog/comment-new [debug
                                    inter/persist-id-counters
                                    (rf/inject-cofx :now)
                                    (rf/inject-cofx :gen-id [:comment])]
 (fn [{:keys [db now id]} [_ path comment]]
   (let [id (str (-> id :id :comment) "-" (-> id :uuid))
         user-id (get-in db [:state :user])
         full-path (assemble-path [:blog :posts (keyword (str (first path)))]
                                  (concat (rest path) [id]))
         sibling-count (count (get-in db full-path))
         comment (merge comment
                        {:ts now
                         :id id
                         :seq-id (inc sibling-count)
                         :user user-id ;todo how auth this?
                         :parent-post (first path)
                         :parent-comment (when (< 1 (count path))
                                           (last path))
                         :score 0})]
       {:dispatch-n [[:blog/comment path comment]
                     [:blog/expand-comment-thread path true]]})))


(rf/reg-event-fx :blog/comment-vote [debug] ;TODO def sub firestore on-snapshot just because (plus for general comments as well)
 (fn [{:keys [db]} [_ user active-user path vote]]   ; other cool stuff could be typing indicator yeah?
   (let [diff (case vote :up 1 :down -1)
         state-path [:state :blog :voted path]
         db-path (assemble-path [:blog :posts (first path)] (rest path) :score)
         post (get-in db [:blog :posts (first path)])
         opposite (case vote :up :down :down :up)
         voted (get-in db state-path)
         vote (if-not (= vote voted) vote false)
         diff (condp = voted
               vote     (- diff)
               opposite (* 2 diff)
               diff)]
     {:db (-> db
              (assoc-in state-path vote)
              (update-in db-path + diff))
      :dispatch-n
      [[:store-> ; store score in comment
        [:blog-posts (str (first path))]
        (assoc-in {} (assemble-path [] (rest path) :score)
                  (+ (get-in db db-path) diff))
        [:comments]]
       [:store->  ; active-user voted
        [:users (:id active-user)]
        (assoc-in {} [:voted path] vote)
        [:voted]]
       [:store-> ; store score in user - but not our user, theirs...
        [:users (:id user)]
        {:karma (+ (:karma user) diff)}
        [:karma]]]})))

; for comment scroll lazy load:
; pull comments one by one, chunked so maybe like first five
; put intersectionobserver on maybe second to last one
; that triggers another equal pull etc

(rf/reg-event-fx :blog/expand-comment-thread
 (fn [{:keys [db]} [_ path expand?]]
   (let [] 
     {:db (assoc-in db [:state :blog :comment-thread-expanded path] expand?)
      :dispatch [:ls/store-val [:blog :comment-thread-expanded path] expand?]})))

(rf/reg-event-fx :blog/adding-comment
 (fn [{:keys [db]} [_ parent-path adding?]]
   (let [] 
     {:db (assoc-in db [:state :blog :adding-comment parent-path] adding?)
      :dispatch [:ls/store-val [:blog :adding-comment parent-path] adding?]})))

