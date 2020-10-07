(ns tolgraven.blog.events
  (:require
    [re-frame.core :as rf]
    [ajax.core :as ajax]))

(def debug (when ^boolean goog.DEBUG rf/debug)) ; doesnt shut off in prod tho so, wtf

(rf/reg-event-fx :page/init-blog [debug]
  (fn [{:keys [db]} _]
    {:dispatch-n
      [(when-not (-> db :content :blog :postsz_test) ; no re-request for this...
         [:http-get {:uri             "/blog/1"
                      :response-format (ajax/raw-response-format)}
           [:blog/set-content]])
       (when-not (-> db :state :blog :page)
         [:state [:blog :page] 1]) ; should only done once tho
       ; [:->css-var! "line-width-vert" "0px"]
       ]})) ; and then kill for main etc... but better if tag pages according to how they should modify css]}))
; (rf/reg-event-db :blog/set-content [debug]
;  (fn [db [_ [response]]]
;    ; (let [{:keys [blog-post/user blog-post/title blog-post/text blog-post/id]}
;    (println response)
;    (let [response (edn/read-string response)]
;      (println response)
;      (assoc-in db [:content :blog :posts]
;              [response]))))
; ; welll this wunt wurk but anyways
(rf/reg-event-db :blog/nav-page
 (fn [db [_ nav]]
   (let [path [:state :blog :page]]
     (if (number? nav)
       (assoc-in db path nav)
       (update-in db path (case nav
                            :prev dec
                            :next inc))))))

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
         sibling-count (count (get-in db path))]
     (println parent-path)
     (println path)
       {:db (update-in db path
                       conj
                       (merge comment
                              {:ts now :id (inc sibling-count) :seq-id id}))})))

