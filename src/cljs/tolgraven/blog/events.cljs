(ns tolgraven.blog.events
  (:require
    [re-frame.core :as rf]
    [re-frame.std-interceptors :refer [path]]
    ; [day8.re-frame.tracing :refer-macros [fn-traced]]
    [clojure.edn :as edn]
    [clojure.walk :as walk]
    [ajax.core :as ajax]
    [tolgraven.interceptors :as inter :refer [debug]]
    [tolgraven.util :as util]))


(rf/reg-event-fx :page/init-blog [debug]
  (fn [{:keys [db]} _]
    (if-not (-> db :state :booted :blog)
     {:dispatch-n [[:blog/state [:page] 0]
                   [:booted :blog]
                   ] ; would be nice to defer (further in?) mount til certain of these run, now loads before happens... so set in initial db
      :firestore/get {:path-collection [:blog-posts]
                      :on-success [:blog/set-content]
                      :on-failure [:diag/new :error "Blog" "can't content"]} ; and then kill for main etc... but better if tag pages according to how they should modify css
      }))) ; and then kill for main etc... but better if tag pages according to how they should modify css]}))

(rf/reg-event-fx :page/init-post-blog [debug]
  (fn [{:keys [db]} _]
    {:dispatch-n [ [:user/close-ui]]})) ; and then kill for main etc... but better if tag pages according to how they should modify css]}))


(rf/reg-event-fx :blog/set-content [debug]
 (fn [{:keys [db]} [_ response]]
   {:db (assoc-in db [:blog :posts]
                  (util/normalize-firestore response))}))

(rf/reg-event-db :blog/state [;debug
                              (path [:state :blog])]
 (fn [blog [_ path v]]
   (assoc-in blog path v)))


(rf/reg-event-db :blog/nav-page ; TODO should also (deferred) fetch content for next/prev/last and any by id directly clickable pages
  [(path [:state :blog :page])]
 (fn [idx [_ nav]]
   (if (number? nav)
     nav
     (case nav :prev (dec idx)
               :next (inc idx)))))

(rf/reg-event-fx :blog/fetch-navigatable-pages
                 [(path [:state :blog :navigatable-pages])]
  (fn [{:keys [db]} [_ _]]
    {:dispatch [:blog/fetch-pages db]}))

(rf/reg-event-fx :blog/fetch-pages
  (fn [{:keys [db]} [_ page-indexes]]
    {:firestore/batch-get?? {}}))

(rf/reg-event-fx :blog/fetch-posts
  (fn [{:keys [db]} [_ indexes]]))

(rf/reg-event-fx :blog/submit-new ; needs to gen an id too, could grab nr of latest on init for seq-id
 (fn [_ [_ input]]
   {:dispatch-n [[:blog/post-new input]  ; [:conj [:blog :posts] input]
                 [:form-field [:post-blog] nil]]})) ;or whatever. also applies (even more!) to comment-ui

(rf/reg-event-fx :blog/post-new [inter/persist-id-counters
                                 (rf/inject-cofx :now)
                                 (rf/inject-cofx :gen-id [:blog])]
 (fn [{:keys [db now id]} [_ post]]
   (let [id (-> id :id :blog)
         post (assoc post :ts now :id id :user (-> post :user :id))]
     {:db (assoc-in db [:blog :posts id] post)
      :dispatch-n [[:store-> [:blog-posts (str id)] post]
                   ; should also store id of blog under under user somehow?
                   [:store-> [:users (-> post :user :id)]
                             {:blog-posts id}
                             [:blog-posts]]
                   ]})))


; {:firestore/get {:path-collection [:blog-comments] ;not working, fails spec?
;                  :where [[:user := "user-id"] ]
;                  :limit 100
;                  :order-by [[:id :desc]]
;                  :start-at ["CA" 1000]
;                  :doc-changes false
;                  :on-success #(prn "Number of comments" (:size %))}}



(defn- assemble-path "Get db path from path pieces"
  [base path & further]
  (let [path (reduce (fn [p id]
                       (into p [:comments (keyword (str id))])) ;needs to be keyword tho blog uses num keys because of keywordize...
                     base path)]
    (if further ; dont want last :comments, instead something else
      (concat path further)
      path)))

(rf/reg-event-fx :blog/comment-new [debug
                                    inter/persist-id-counters
                                    (rf/inject-cofx :now)
                                    (rf/inject-cofx :gen-id [:comment])]
 (fn [{:keys [db now id]} [_ path comment]]
   (let [id (-> id :id :comment)
         full-path (assemble-path [:blog :posts (first path)]
                                  (concat (rest path) [id]))
         sibling-count (count (get-in db full-path))
         comment (merge comment
                        {:ts now
                         :id id
                         :seq-id (inc sibling-count)
                         :user (get-in db [:state :user]) ;todo how auth this?
                         :score 0})]
       {:db (-> db
                (assoc-in full-path comment)) ; need to vec else first nil conj creates a list which cant be update-in'd
        :dispatch
        [:store->
         [:blog-posts (str (first path))]
         (assoc-in {} (assemble-path []
                                     (concat (rest path) [id]))
                   comment)
         [:comments]]})))



(rf/reg-event-fx :blog/comment-vote ;TODO def sub firestore on-snapshot just because (plus for general comments as well)
 (fn [{:keys [db]} [_ path vote]]   ; other cool stuff could be typing indicator yeah?
   (let [diff (case vote :up 1 :down -1)
         state-path [:state :blog :voted path]
         db-path (assemble-path [:blog :posts (first path)] (rest path) :score)
         opposite (case vote :up :down :down :up)
         voted (get-in db state-path)
         diff (condp = voted
               vote     (- diff)
               opposite (* 2 diff)
               diff)]
     {:db (-> db
              (assoc-in state-path (if-not (= vote voted) vote false))
              (update-in db-path + diff))
      :dispatch
      [:store->
       [:blog-posts (str (first path))]
       (assoc-in {} (assemble-path [] (rest path) :score)
                       (+ (get-in db db-path)
                          diff))
       [:comments]]})))

; for comment scroll lazy load:
; pull comments one by one, chunked so maybe like first five
; put intersectionobserver on maybe second to last one
; that triggers another equal pull etc
