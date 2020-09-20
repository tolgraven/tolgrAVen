(ns tolgraven.events
  (:require
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [reitit.frontend.easy :as rfe]
    [cljs-time.core :as ct]
    [reitit.frontend.controllers :as rfc]
    [tolgraven.util :as util]))


;;dispatchers from luminus, see if any useful...
(rf/reg-event-db :common/navigate
  (fn [db [_ match]]
    (let [old-match (:common/route db)
          new-match (assoc match :controllers
                                 (rfc/apply-controllers (:controllers old-match) match))]
      (assoc db :common/route new-match))))

(rf/reg-fx :common/navigate-fx!
  (fn [[k & [params query]]]
    (rfe/push-state k params query)))

(rf/reg-event-fx :common/navigate!
  (fn [_ [_ url-key params query]]
    {:common/navigate-fx! [url-key params query]}))

(rf/reg-event-db :set-docs
  (fn [db [_ docs]]
    (assoc db :docs docs)))

(rf/reg-event-fx :fetch-docs
  (fn [_ _]
    {:http-xhrio {:method          :get
                  :uri             "/docs"
                  :response-format (ajax/raw-response-format)
                  :on-success       [:set-docs]}}))

(rf/reg-event-db :common/set-error
  (fn [db [_ error]]
    (assoc db :common/error error)))

(rf/reg-event-fx :page/init-home
  (fn [_ _]
    {:dispatch [:fetch-docs]}))

;;subscriptions
(rf/reg-sub :common/route
  (fn [db _]
    (-> db :common/route)))
(rf/reg-sub :common/page-id
  :<- [:common/route]
  (fn [route _]
    (-> route :data :name)))
(rf/reg-sub :common/page
  :<- [:common/route]
  (fn [route _]
    (-> route :data :view)))
(rf/reg-sub :common/error
  (fn [db _]
    (:common/error db)))

(rf/reg-sub :docs
  (fn [db _]
    (:docs db)))




(rf/reg-sub :get-css-var
 (fn [db [_ var-name]]
   (util/<-css-var var-name)))

; (println @(rf/subscribe [:get-css-var "--header-with-menu-height"]))
; (js/console.log @(rf/subscribe [:get-css-var "--header-with-menu-height"]))
(rf/reg-event-fx :set-css-var!
  (fn [{:as cofx :keys [db]} [_ var-name value]]
    (util/->css-var var-name value)))

(rf/reg-event-fx :menu ;; this why better sep. can then inject css var and not sub? i somehow remeber that being badd
  (fn [{:as cofx :keys [db]} [_ state]]
    (let [open-height @(rf/subscribe [:get-css-var "--header-with-menu-height"])
          closed-height @(rf/subscribe [:get-css-var "--header-height"])
          difference (->> (map js/parseFloat [open-height closed-height])
                          (apply -)
                          (* 0.5))]
      {:db (assoc-in db [:menu] state)
       :dispatch-n
        [[:set-css-var! "--header-height-current"
                        (if state open-height closed-height)]]
        :dispatch-later {:ms 250 :dispatch [:scroll :by (if state (- difference) difference)]}})))
;; XXX otherwise will have to uh, read var best we can and dispatch scroll event?

(rf/reg-event-fx
 :scroll
 (fn [_ [_ kind value]] ; rem or elem
   (case kind
     :by (util/scroll-by value)
     :to (util/scroll-to value))))
; (rf/reg-event-fx :state ;; this why better sep. can then inject css var and not sub? i somehow remeber that being badd
;   (fn [{:as cofx :keys [db]} [_ item state]]
;     (case item
;       :menu {:dispatch [:set-css-var! "--header-height-current"
;                                       @(rf/subscribe [:get-css-var (if state
;                                                                      "--header-with-menu-height"
;                                                                      "--header-height")])]}))) ; tho should be auto calced properly...
    ; (case item
    ;   :menu (let [header-height (if state "18rem" "6rem")] ; best if only set pointer css var ref to curr
    ;           ; if can actually get the css-offset tricky thing to work...
    ;           {:dispatch-n [[:state :header-height header-height]]})))) ; tho should be auto calced properly...

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
  (fn [{:keys [db]} [_ opts & [handler error]]]
    {:db (assoc db :show-twirly true)   ;; set something to indicate request is underway
     :http-xhrio
     (merge
      {:method          :get
       :uri             "https://api.github.com/orgs/day8"
       :timeout         2000                                           ;; optional see API docs
       :response-format (ajax/json-response-format {:keywords? true})  ;; IMPORTANT!: You must provide this.
       :on-success      [(or handler :default-http-result) :success]
       :on-failure      [(or error   :default-http-error)  :error]}
      opts)}))

(rf/reg-event-fx ::http-post
  (fn [{:keys [db]} [_ opts & [handler error]]]
    (let [timeout 5000]
     {:http-xhrio
      {:method          :post
       :uri             (str "http://localhost:3449/api"
                             "/plus") #_"https://httpbin.org/post"
       ; :uri             (str "http://localhost:16000/"
       ;                       "ui-event/3/" "stopButton") #_"https://httpbin.org/post"
       ; :params          {:x 1 :y 2}
       :params          "x 1 y 2"
       :timeout         timeout
       ; :format          (ajax/json-request-format)
       :response-format (ajax/json-response-format {:keywords? true})
       :on-success      [:default-http-result]
       :on-failure      [:default-http-error]}
      :timeout [:yada-yada
                :timeout timeout]})))

; To make multiple requests, supply a vector of options maps:
; {:http-xhrio [ {...}
;                {...}]}

(rf/reg-event-fx :default-http-result
 (fn [db [_ res]] (println res)))
(rf/reg-event-fx :default-http-error
 (fn [db [_ res]] (println res)))



(rf/reg-cofx :now         #(assoc % :now (ct/now)))

(defonce diag-id-counter (atom 0)) ;why not just keep this in db as well tho?
(rf/reg-cofx :diag/gen-id #(assoc % :id (swap! diag-id-counter inc)))

(rf/reg-event-fx :diag/new  ;this needs a throttle lol
 [#_debug
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
;;random maybe:
;{:origin :server ;first thought just react but yeah nice
; :strategies {:undo [[:an-undo-button]]
;              :ignore [:silence]
;              :open-ide [:get-line-last-error error]} }

(rf/reg-event-db :diag/unhandled #_[debug]
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


