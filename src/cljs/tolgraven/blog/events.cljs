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
   #_(let [response (edn/read-string response)]
     (println response)
     #_(assoc-in db [:content :blog :posts]
             [response]))))

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
 (fn [db [_ open? parent-id-path]]
   (assoc-in db [:state :blog :make-comment-view] [open? parent-id-path]))) ; i guess post-id either a blog post id, or vec of blog -> parent comment(s)


(rf/reg-event-fx :blog/comment-new [debug
                                    (rf/inject-cofx :now)
                                    (rf/inject-cofx :gen-uuid)]
 (fn [{:keys [db now id]} [_ [blog-id & parent-path] comment]]
   (let [appender #(reduce (fn [p id]
                              (into p [(dec id) :comments]))
                            % parent-path) ; append any nested pathings
         path (cond-> [:content :blog :posts (dec blog-id) :comments]
                (seq parent-path) appender)
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


