(ns tolgraven.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [day8.re-frame.http-fx]
    ; [day8.re-frame.tracing :refer-macros [fn-traced]]
    [akiroz.re-frame.storage :refer [reg-co-fx! persist-db-keys]]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [tolgraven.util :as util]
    [tolgraven.blog.events]
    [tolgraven.user.events]
    [tolgraven.cofx :as cofx]
    [clojure.string :as string]
    ; [muuntaja.core :as m]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(defn reg-event-db ; wrapper
  [event-id handler]
  (rf/reg-event-fx
    event-id
    [(persist-db-keys :app [:state :options :users :content])]
    (fn [{:keys [db]} event-vec]
      {:db (handler db event-vec)})))
; (rf/reg-fx :dispatch-staggered) ; ya kno. stagger -later, maybe optionally awaiting comfirmation of :done for dispatching dispatches. but the asdync flow thing prob already does most of that?

; re-frisk occasionally throws 10MB long "trace while storing" errors so def dont try to display that shit.
; (rf/set-loggers!  {:warn  (fn [& args]
;                               (util/log :warning "Warning" (apply str args)))   
;                    :error   (fn [& args]
;                               (util/log :error "Error" (apply str args))) })

(rf/reg-event-fx
 :common/start-navigation ; XXX obviously navigation has to happen straight away. but two components (old, new) always rendered, one slid away...
 ; TODO prevent from starting nav when same page (once figure out anchors)
 (fn [{:as cofx :keys [db]} [_ match]]
   (let [old-match (:common/route db)]
     {:dispatch [:transition/out old-match]
      :dispatch-later {:ms (or (-> db :options :transition-time) 160)
                       :dispatch [:common/navigate match]}})))

(rf/reg-fx :document/set-title
  (fn [title]
    (set! js/document.title title)))

(rf/reg-event-fx :common/navigate
  (fn [{:as cofx :keys [db]} [_ match]]
    (let [old-match (:common/route db)
          new-match (assoc match :controllers
                                 (rfc/apply-controllers (:controllers old-match) match))]
      {:db (-> db
               (assoc :common/route new-match)
               (assoc :common/route-last old-match)
               (assoc-in [:exception :page] nil)) ; reset exception state since using same error boundary for all pages
       :dispatch-n [[:transition/in new-match]
                    [:scroll/to "linktotop"]]
       :document/set-title (->> new-match :data :name
                                name string/capitalize
                                (str (get-in db [:content :title] "tolgrAVen") " - "))})))

(rf/reg-fx :common/navigate-fx!
  (fn [[k & [params query]]]
    (rfe/push-state k params query)))

(rf/reg-event-fx :common/navigate!
  (fn [_ [_ url-key params query]]
    {:common/navigate-fx! [url-key params query]}))


; (rf/reg-event-fx :run-in 
;   (fn [_ [_ id-key ms f & args]]
;     {:dispatch-later
;      {:ms ms
;       :dispatch [:run-fn (into [id-key f] args)]}}))

; (rf/reg-event-fx :run-fn
;   (fn [_ [_ id-key f & args]]
;     (apply f args)
;     nil))

(rf/reg-event-fx :run-in! ; trying to figure out why'd be bad idea.
; certainly would be if over-used (js cb hell) but for like, what would otherwise be:
; define evt, define second evt, dispatch-later to second.
; just dispatch to a generic evt that takes a fn. basically the :get of events I suppose, bad in long run
; but can do some things easy quick - dont have to define stuff for every little document/window etc callout.
; and passing an id we can still see what is what so..  plus get to close over stuff etc yada.
  (fn [_ [_ id-key ms f & args]]
    {:dispatch-later
     {:ms ms
      :dispatch [:run-fn! (into [id-key f] args)]}}))

(rf/reg-event-fx :run-fn!
  (fn [_ [_ id-key f & args]]
    {:run-fn-fx! (into [id-key f] args)}))

(rf/reg-fx :run-fn-fx!
  (fn [[id-key f & args]]
    (apply f args)))


(rf/reg-event-fx :focus-element
  (fn [_ [_ elem-id]]
    ; (r/after-render #(some-> (util/elem-by-id elem-id) .focus))
    {:focus-to-element elem-id}))
(rf/reg-fx :focus-to-element
  (fn [elem-id] 
    (r/after-render #(some-> (util/elem-by-id elem-id) .focus))))

(defn assoc-in-factory [base-path]
  (fn [db [_ path value]]
    (assoc-in db (into base-path path) value)))
; (doseq [k [:content :state :option :exception :form-field]]
;   (rf/reg-event-db :state
;     (assoc-in-factory [k])))

(rf/reg-event-db :content ;[debug]
                 (assoc-in-factory [:content]))

(rf/reg-event-db :state
  (fn [db [_ path value]]
    (assoc-in db (into [:state] path) value)))

(rf/reg-event-db :option ;[debug]
  (fn [db [_ path value]]
    (assoc-in db (into [:options] path) value)))

(rf/reg-event-fx :debug ;[debug]
  (fn [{:keys [db]} [_ path value]]
    (case path
      [:layers] (-> (js/document.querySelector "main")
                    .-classList
                    (.toggle "debug-layers")))
    {:db (assoc-in db (into [:state :debug] path) value)}))


(rf/reg-event-db :exception
  (assoc-in-factory [:state :exception]))

(rf/reg-event-db :form-field ;gets spammy lol. maybe internal til on blur hey...
  (assoc-in-factory [:state :form-field]))


(rf/reg-event-fx :reloaded
 (fn [db [_ _]]
   ; {:dispatch [:diag/new :info "JS" "Reloaded"]}))
   {:db nil}))


; renamed store-> not fire->, should work to hide fire behind stuff
; so can swap out easier
(rf/reg-event-fx :store->
  (fn [_ [_ path data merge-fields]]
    {:firestore/set {:path path :data data
                     :set-options
                     (when merge-fields
                       {:merge true :merge-fields merge-fields})}}))
; other thing could do is combo app-db/fire setter/getter
; so <-$ subs topic and tries grab from local, then far
; while dispatch will store value in both db and send to fire.
; in one op.
; that would be for eg if thousands of objects so makes much more sense to
; also batch-fetch early in page boot.
; but then in component still always have latest value.
; if that actually (easily) possible hmm
; 

(rf/reg-event-fx :fb/fetch-settings [debug]
  (fn [{:keys [db]} _]
    {:dispatch [:http/get {:uri "/api/firebase-settings"}
                [:option [:firebase]]]}))




; PROBLEM: would obviously want to trigger fetch on start-navigation,
; not navigate...
(rf/reg-event-fx :page/init-docs ;[debug]
  (fn [{:keys [db]} _]
    {:dispatch-n
      [(when-not (-> db :content :docs :md) ; no re-request for this...
         [:http/get {:uri             "/docs"
                      :response-format (ajax/raw-response-format)}
           [:content [:docs :md]]])
       ; [:->css-var! "line-width-vert" @(rf/subscribe [:get-css-var "line-width"])]
       ]})) ; and then kill for main etc... but better if tag pages according to how they should modify css]}))

(rf/reg-event-fx :page/init-home ;[debug]
 (fn [_ _]
   {:dispatch-n [[:state [:is-loading] false]
                 [:state [:is-personal] false]
                 ; [:->css-var! "line-width-vert" "0px"]
                 ]})) ; be careful w dispatch-n, entire chain stops if one throws (like here w css-var...)


(rf/reg-event-fx :->css-var!
  (fn [_ [_ var-name value]]
    (util/->css-var var-name value)
    nil)) ;avoid it trying to parse heh

(rf/reg-event-db :transition/out ; if all transitions same (fade or w/e) dont really need pass match... and, if specific order or similar matters, need pass both.
  (fn [db [_ activity direction]]  ; would just set something in state that then sets css class.
    (assoc-in db [:state :transition] true))) ; now just generic

(rf/reg-event-db :transition/in ; if all transitions same (fade or w/e) dont really need pass match... and, if specific order or similar matters, need pass both.
  (fn [db [_ direction match]]  ; would just set something in state that then sets css class.
    (assoc-in db [:state :transition] false))) ; now just generic

(rf/reg-event-fx :menu ;; this why better sep. can then inject css var and not sub? i somehow remeber that being badd
  (fn [{:as cofx :keys [db]} [_ state]]
    (let [open-height   @(rf/subscribe [:get-css-var "header-with-menu-height"])
          closed-height @(rf/subscribe [:get-css-var "header-height"]) ;TODO should rather be set from here with data-attr? ideally depends on content
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

; these should technically be reg-fx but fx seem flaky as fuck wtf?
; common/navigate! stopped working, focus-element works as fx but these dont.
; double up for now lol.
(rf/reg-event-fx :scroll/by
 (fn [_ [_ value & [in-elem-id]]] ; rem (should handle % too tho?), id of container..
   (util/scroll-by value in-elem-id) ;XXX remove once figure out what's wrong with reg-fx
   {:scroll/by [value in-elem-id]}))
(rf/reg-fx :scroll/by
 (fn [value & [in-elem-id]]
   (util/scroll-by value in-elem-id)))

(rf/reg-event-fx :scroll/to
 (fn [_ [_ id & [offset]]]
   (util/scroll-to id offset) ;XXX remove once figure out what's wrong with reg-fx
   {:scroll/to [id offset]}))
(rf/reg-fx :scroll/to
 (fn [id & [offset]]
   (util/scroll-to id offset)))


(rf/reg-event-fx :id-counters/handle
  (fn [{:keys [db]} [_ state]]
    {:dispatch [:diag/new :info "ID-counters" (str "Restored to " state)]
     :id-counters/set! state}))

(rf/reg-event-fx :id-counters/fetch
  (fn [{:keys [db]} [_ _]]
    {:firebase/read-once {:path [:id-counters]
                          :on-success [:id-counters/handle]}}))

(rf/reg-event-db :loading/on ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [db [_ category]]
   (assoc-in db [:state :is-loading category] true)))
(rf/reg-event-db :loading/off ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [db [_ category]]
   (assoc-in db [:state :is-loading category] false)))

(rf/reg-event-db :booted ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [db [_ page]]
   (assoc-in db [:state :booted page] true)))


(rf/reg-event-fx :init ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [{:as cofx :keys [db]} [_ dispatch-once dispatch-each]]
  (let [dispatches (concat dispatch-each ;try each first?
                           (when-not (get-in db [:done-init])
                            (conj dispatch-once
                                  [:set [:done-init] true])))]
   {:dispatch-n dispatches})))

; generic helpers for rapid prototyping.
; NOT FOR LONG-TERM USE if straight to data path not viable
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
(rf/reg-event-db :update-in
 (fn [db [_ path & args]]
  (apply update-in db path args)))

(defn get-http-fn "Return fn used for http-get/post"
  [kind & [extra-defaults]]
  (fn http-fn [{:keys [db]} [_ opts & [on-success on-error]]]
    (let [cleanup [:state [:is-loading] false]] ; set something to indicate request is underway
      {:dispatch [:state  [:is-loading] true]   ;; tho usually want this locally so figure out. by passing path frag maybe...
       :http-xhrio
       (merge
        {:method          kind
         :timeout         8000                                           ;; optional see API docs
         :response-format (ajax/transit-response-format)  ;; IMPORTANT!: You must provide this.
         ; :response-format (ajax/json-response-format {:keywords? true})  ;; IMPORTANT!: You must provide this.
         :on-success      [:http-result-wrapper
                           (or on-success [:default-http-result]) cleanup]
         :on-failure      [:http-result-wrapper
                           (or on-error   [:default-http-error]) cleanup]}
        extra-defaults
        opts)})))

(rf/reg-event-fx :http/get [debug]
  (get-http-fn :get))

(rf/reg-event-fx :http/post [debug]
  (get-http-fn :post
                              ))
               ; {:format (ajax/transit-request-format)}))

(rf/reg-event-fx :http/put [debug]
  (get-http-fn :put
               {:multipart-params :something})) ;file upload..


(rf/reg-event-fx :default-http-result
 (fn [db [_ res]]
   {:dispatch [:diag/new :debug "HTTP" (str res)]}))
(rf/reg-event-fx :default-http-error
 (fn [db [_ {:as res :keys [uri status status-text failure]}]]
   {:dispatch [:diag/new :error "HTTP" (str status " " status-text ": " uri)]}))

(rf/reg-event-fx :http-result-wrapper
 (fn [db [_ handler cleanup res]]
   {:dispatch-n [(into handler [res])
                 cleanup]}))


(rf/reg-event-fx :run-highlighter!
 (fn [_ [_ elem]]
   (when elem
     {:run-highlighter-fx! elem})))

(rf/reg-fx :run-highlighter-fx!
 (fn [elem]
   (util/run-highlighter! "pre" elem)))


(rf/reg-event-fx :diag/new  ;this needs a throttle lol
 [(rf/inject-cofx :now)
  (rf/inject-cofx :gen-id [:diag])]
 (fn [{:keys [db now id]} [_ level title message {:keys [sticky? actions]}]] ;error, warning, info
  (let [id (-> id :id :diag)]
    (merge
     {:db (update-in db [:diagnostics :messages]
                     assoc id {:level   level
                               :id      id
                               :title   title
                               :message message
                               :time    now
                               :actions actions})}
     (when (not= level :debug) ;also filtered in hud tho..
       {:dispatch    [:diag/unhandled :add    id]
        :dispatch-later
        [(when-not sticky?
           {:dispatch  [:diag/unhandled :remove id]
            :ms (* 1000 (get-in db [:options :hud :timeout]))})]}))))) ;tho can always get removed earlier by us...

(rf/reg-event-db :diag/unhandled
 (fn [db [_ action id]]
  (case action
   :add    (update-in db [:diagnostics :unhandled] conj id)
   ; :closing ;however this'd be achieved. nice fade-out. but if enough things call for it might as well go figure transition-group
   :remove (update-in db [:diagnostics :unhandled] #(-> % set (disj id))))))

(rf/reg-event-db :hud
 (fn [db [_ action id]]
  (case action
    :modal (if (= id :remove)
             (update db :hud dissoc :modal)
             (assoc-in db [:hud :modal] id)))))


