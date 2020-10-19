(ns tolgraven.blog.events
  (:require
    [re-frame.core :as rf]
    [re-frame.std-interceptors :refer [path]]
    [clojure.edn :as edn]
    [ajax.core :as ajax]
    [tolgraven.util :as util]))

(def debug (when ^boolean goog.DEBUG rf/debug)) ; doesnt shut off in prod tho so, wtf

(rf/reg-event-fx :page/init-blog [debug]
  (fn [{:keys [db]} _]
    {:dispatch-n
      [(when-not (-> db :content :blog :postsz_test) ; no re-request for this...
         [:http-get {:uri             "/blog/1"
                     :response-format (ajax/detect-response-format)}
           [:blog/set-content]])
       (when-not (-> db :state :blog :page)
         [:state [:blog :page] 0]) ; would be nice to defer (further in?) mount til certain of these run, now loads before happens... so set in initial db
       ; [:->css-var! "line-width-vert" "0px"]
       ]})) ; and then kill for main etc... but better if tag pages according to how they should modify css]}))

(rf/reg-event-db :blog/set-content [debug]
 (fn [db [_ [response]]]
   ; (let [{:keys [blog-post/user blog-post/title blog-post/text blog-post/id]}
   (println response)
   (let [response (edn/read-string response)]
     (println response)
     ; (assoc-in db [:content :blog :posts] [response]))))
     (update-in db [:content :blog :posts] conj response))))


(rf/reg-event-db :blog/state [debug
                              (path [:state :blog])]
 (fn [blog [_ path v]]
   (assoc-in blog path v)))

(rf/reg-event-db :blog/nav-page
  [(path [:state :blog :page])]
 (fn [idx [_ nav]]
   (if (number? nav)
     nav
     (case nav :prev (dec idx)
               :next (inc idx)))))

; (rf/reg-event-db :blog/nav-has? ; :next :prev. for disabling btns etc

(rf/reg-event-db :blog/post-ui-open
 (fn [db [_ open?]]
   (assoc-in db [:state :blog :make-post-view] open?)))

(rf/reg-event-fx :blog/submit-new ; needs to gen an id too
 (fn [_ [_ {:keys [] :as input}]]
   {:dispatch-n [[:blog/post-new input]  ; [:conj [:blog :posts] input]
                 [:blog/post-ui-open false] ]})) ;or whatever. also applies (even more!) to comment-ui

(rf/reg-event-fx :blog/post-new [debug
                                 (rf/inject-cofx :now)
                                 (rf/inject-cofx :gen-uuid)]
 (fn [{:keys [db now comment-id]} [_ post]]
   (assoc-in db [:content :blog :1]
             (assoc post :ts now :id comment-id))))


(rf/reg-event-db :blog/comment-ui-open
                 [(path [:state :blog :adding-comment])]
 (fn [open [_ open? parent-id-path]]
   (assoc open parent-id-path open?))) ; i guess post-id either a blog post id, or vec of blog -> parent comment(s)

(defn- assemble-path "Get db path from path pieces"
  [path & further]
  (let [base-path [:content :blog :posts]
        path (reduce (fn [p id]
                       (into p [(dec id) :comments]))
                     base-path path)]
    (if further ; dont want last :comments, instead something else
      (into (vec (butlast path)) further)
      path)))

(rf/reg-event-fx :blog/comment-new [debug
                                    (rf/inject-cofx :now)
                                    (rf/inject-cofx :gen-uuid)]
 (fn [{:keys [db now id]} [_ path comment]]
   (let [path (assemble-path path)
         sibling-count (count (get-in db path))
         comment (merge comment
                        {:ts now
                         :id (inc sibling-count)
                         :seq-id id
                         :score 0})]
       {:db (update-in db path
                       (comp vec conj) ; need to vec else first nil conj creates a list which cant be update-in'd
                       comment)
        :dispatch [:http-post {:uri "/add-comment"
                               :params comment
                               }]})))

(rf/reg-event-db :blog/comment-vote [debug]
 (fn [db [_ path vote]]
   (let [diff (case vote :up 1 :down -1)
         state-path [:state :blog :voted path]
         opposite (case vote :up :down :down :up)
         voted (get-in db state-path)
         diff (condp = voted
               vote     (- diff)
               opposite (* 2 diff)
               diff)]
     (-> db
         (assoc-in state-path (if-not (= vote voted) vote false))
         (update-in (assemble-path path :score)
                    + diff)))))

; for comment scroll lazy load:
; pull comments one by one, chunked so maybe like first five
; put intersectionobserver on maybe second to last one
; that triggers another equal pull etc
