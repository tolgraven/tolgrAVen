(ns tolgraven.events
  (:require
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [tolgraven.util :as util]
    [cljs-time.core :as ct]
    [cljs-time.format :refer [formatters formatter unparse]]))

(def debug (when ^boolean goog.DEBUG rf/debug))

; (rf/reg-fx :dispatch-staggered) ; ya kno. stagger -later, maybe optionally awaiting comfirmation of :done for dispatching dispatches. but the asdync flow thing prob already does most of that?

(rf/reg-event-fx
 :common/start-navigation
 (fn [{:as cofx :keys [db]} [_ match]]
   (let [old-match (:common/route db)]
     {:dispatch [:transition/out old-match]
      :dispatch-later {:ms (or (-> db :options :transition-time) 160)
                       :dispatch [:common/navigate match]}})))

;;dispatchers from luminus, see if any useful...
(rf/reg-event-fx :common/navigate
  (fn [{:as cofx :keys [db]} [_ match]]
    (let [old-match (:common/route db)
          new-match (assoc match :controllers
                                 (rfc/apply-controllers (:controllers old-match) match))]
      {:db (assoc db :common/route new-match)
       :dispatch-n [[:transition/in new-match]
                    [:scroll/to "linktotop"]]})))

(rf/reg-fx :common/navigate-fx!
  (fn [[k & [params query]]]
    (rfe/push-state k params query)))

(rf/reg-event-fx :common/navigate! ; never actually called, check template...
  (fn [_ [_ url-key params query]]
    {:common/navigate-fx! [url-key params query]}))


(rf/reg-event-db :content [debug]
  (fn [db [_ path value]]
    (assoc-in db (into [:content] path) value)))

(rf/reg-event-db :state [debug]
  (fn [db [_ path value]]
    (assoc-in db (into [:state] path) value)))

(rf/reg-event-db :option [debug]
  (fn [db [_ path value]]
    (assoc-in db (into [:options] path) value)))

(rf/reg-event-fx :debug [debug]
  (fn [{:keys [db]} [_ path value]]
    (case path
      [:layers] (-> (js/document.querySelector "main")
                    .-classList
                    ; (.set "debug-layers" false)))
                    (.toggle "debug-layers")))
    {:db (assoc-in db (into [:state :debug] path) value)}))

; (rf/reg-event-fx :debug/layers [debug]
;   (fn [{:keys [db]} [_ value]]
;     (case path
;       ; [:layers] (.toggle js/document.body.classList "debug-layers"))
;       [:layers] (-> (js/document.querySelector "main")
;                     .-classList
;                     (.set "debug-layers")))
;     {:db (assoc-in db (into [:state :debug] path) value)}))


; PROBLEM: would obviously want to trigger fetch on start-navigation,
; not navigate...
(rf/reg-event-fx :page/init-docs [debug]
  (fn [{:keys [db]} _]
    {:dispatch-n
      [(when-not (-> db :content :docs :md) ; no re-request for this...
         [::http-get {:uri             "/docs"
                      :response-format (ajax/raw-response-format)}
           [:content [:docs :md]]])
       [:->css-var! "line-width-vert" @(rf/subscribe [:get-css-var "line-width"])]]})) ; and then kill for main etc... but better if tag pages according to how they should modify css]}))

(rf/reg-event-fx :page/init-home [debug]
 (fn [_ _]
   {:dispatch-n [[:->css-var! "line-width-vert" "0px"]
                 [:state [:is-personal] false]]}))


(rf/reg-event-fx :->css-var!
  (fn [_ [_ var-name value]]
    (util/->css-var var-name value)))

(rf/reg-event-db :transition/out ; if all transitions same (fade or w/e) dont really need pass match... and, if specific order or similar matters, need pass both.
  (fn [db [_ activity direction]]  ; would just set something in state that then sets css class.
    (assoc-in db [:state :transition] true))) ; now just generic

(rf/reg-event-db :transition/in ; if all transitions same (fade or w/e) dont really need pass match... and, if specific order or similar matters, need pass both.
  (fn [db [_ direction match]]  ; would just set something in state that then sets css class.
    (assoc-in db [:state :transition] false))) ; now just generic

(rf/reg-event-fx :menu ;; this why better sep. can then inject css var and not sub? i somehow remeber that being badd
  (fn [{:as cofx :keys [db]} [_ state]]
    (let [open-height   @(rf/subscribe [:get-css-var "header-with-menu-height"])
          closed-height @(rf/subscribe [:get-css-var "header-height"])
          difference (->> (map js/parseFloat [open-height closed-height])
                          (apply -)
                          (* 0.5))]
      {:dispatch-n
        [[:state [:menu] state]
         [:->css-var! "header-height-current"
                      (if state open-height closed-height)]]
        :dispatch-later {:ms 250
                         :dispatch [:scroll/by (cond-> difference state -)]}}))) ;;haha silly.
;; XXX otherwise will have to uh, read var best we can and dispatch scroll event?

(rf/reg-event-fx :scroll/by
 (fn [_ [_ value & [id]]] ; rem (should handle % too tho?), id of container..
   (util/scroll-by value id)))

(rf/reg-event-fx :scroll/to
 (fn [_ [_ id & [offset]]]
   (util/scroll-to id offset)))

(defonce uuid-counter (atom 0)) ;js has its own id gen thing so use that maybe. but no sequential then?
(rf/reg-cofx :gen-uuid #(assoc % :id (swap! uuid-counter inc)))
; more bastant vs using db and could append meta on cat for lookup?
; works either way rather than many individual cause always increased vs last of kind
; AFA remember from pf it's more like, gen a temp id here in case of failures etc,
; then once is in db that becomes truth (and likely differs)
(rf/reg-event-db :user/login-ui-open
 (fn [db [_ open?]]
   (assoc-in db [:state :user :log-in-view] open?)))

(rf/reg-event-fx :user/request-login
 (fn [{:keys [db]} [_ info]]
   {:dispatch [::http-post ]}))

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
 (fn [{:keys [db now id]} [_ parent-id comment]]
   (println now id parent-id comment) ;empty...
   (let [path [:content :blog :posts (dec parent-id) :comments]]
     (when (get-in db path)
       {:db (update-in db path
                       conj
                       (merge comment {:ts now :id id}))}))))

;; SOME STUFF FROM CUE-DB
(rf/reg-event-fx :init ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [{:as cofx :keys [db]} [_ dispatch-once dispatch-each]]
  (let [dispatches (concat dispatch-each ;try each first?
                           (when-not (get-in db [:done-init])
                            (conj dispatch-once
                                  [:set [:done-init] true])))]
   {:dispatch-n dispatches})))

(rf/reg-event-db :set
 (fn [db [_ path value]]
  (assoc-in db path value)))
(rf/reg-event-db :unset
 (fn [db [_ path]]
  (update-in db (butlast path) dissoc (last path))))
(rf/reg-event-db :toggle
 (fn [db [_ path]]
  (update-in db path not)))
(rf/reg-event-db :conj
 (fn [db [_ path value]]
  (update-in db path conj value)))
(rf/reg-event-db :pop
 (fn [db [_ path]]
  (update-in db path pop)))

(rf/reg-event-fx ::http-get
                 [debug]
  (fn [{:keys [db]} [_ opts & [handler on-error]]]
    (let [cleanup [:set [:state :is-loading] false]] ; set something to indicate request is underway
      {:dispatch [:set [:state :is-loading] true]   ;; tho usually want this locally so figure out. by passing path frag maybe...
       :http-xhrio
       (merge
        {:method          :get
         :uri             "https://api.github.com/orgs/day8"
         :timeout         2000                                           ;; optional see API docs
         :response-format (ajax/json-response-format {:keywords? true})  ;; IMPORTANT!: You must provide this.
         :on-success      [::http-result-wrapper (or handler  [:default-http-result]) cleanup]
         :on-failure      [::http-result-wrapper (or on-error [:default-http-error]) cleanup]}
        opts)})))

(rf/reg-event-fx ::http-post
  (fn [{:keys [db]} [_ opts & [handler error]]]
    (let [timeout 5000]
     {:http-xhrio
      {:method          :post
       :uri             (str "http://localhost:3449/api" "/plus")
       :params          "x 1 y 2"
       :timeout         timeout
       :response-format (ajax/json-response-format {:keywords? true})
       :on-success      [:default-http-result]
       :on-failure      [:default-http-error]}
      :timeout [:yada-yada
                :timeout timeout]})))
; To make multiple requests, supply a vector of options maps:
; {:http-xhrio [ {...}
;                {...}]}
(rf/reg-event-fx :default-http-result
 (fn [db [_ res]]
   (update-in db [:http :result] conj res)))
(rf/reg-event-fx :default-http-error
 (fn [db [_ {:as res :keys [uri status status-text failure]}]]
   {:dispatch [:diag/new :error status status-text]}))

(rf/reg-event-fx ::http-result-wrapper
 (fn [db [_ handler cleanup res]]
   {:dispatch-n [(into handler [res])
                 cleanup]}))


(rf/reg-event-fx :run-highlighter!
 (fn [_ [_ ref]]
   (util/run-highlighter! "pre" ref)))


(rf/reg-cofx :now         #(assoc % :now (ct/now)))

(defonce diag-id-counter (atom 0)) ;why not just keep this in db as well tho?
(rf/reg-cofx :diag/gen-id #(assoc % :id (swap! diag-id-counter inc)))

(rf/reg-event-fx :diag/new  ;this needs a throttle lol
 [debug
  (rf/inject-cofx :now)
  (rf/inject-cofx :diag/gen-id)] ;or guess id things make more sense centrally ish
 (fn [{:keys [db now id]} [_ level title message actions]] ;error, warning, info
  (merge
   {:db (update-in db [:diagnostics :messages]
                  assoc id {:level   level
                            :id      id
                            :title   title
                            :message message
                            :time    now
                            :actions actions})}
   (when (not= level :debug)
    {:dispatch    [:diag/unhandled :add    id]
     :dispatch-later
     [{:dispatch  [:diag/unhandled :remove id]
       :ms (* 1000 (get-in db [:options :hud :timeout]))}]})))) ;tho can always get removed earlier by us...

(rf/reg-event-db :diag/unhandled [debug]
 (fn [db [_ action id]]
  (case action
   :add    (update-in db [:diagnostics :unhandled] conj id)
   :remove (update-in db [:diagnostics :unhandled] #(-> % set (disj id))))))

(rf/reg-event-db :hud
 (fn [db [_ action id]]
  (case action
   :modal (if (= id :remove)
           (update db :hud dissoc :modal)
           (assoc-in db [:hud :modal] id)))))


