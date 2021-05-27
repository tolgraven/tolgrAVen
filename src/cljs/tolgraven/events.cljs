(ns tolgraven.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [day8.re-frame.http-fx]
    [com.degel.re-frame-firebase :as firebase]
    ; [day8.re-frame.tracing :refer-macros [fn-traced]]
    [day8.re-frame.async-flow-fx :as async-flow-fx]
    [akiroz.re-frame.storage :as localstore]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [tolgraven.util :as util]
    [tolgraven.blog.events]
    [tolgraven.user.events]
    [tolgraven.strava.events]
    [tolgraven.instagram.events]
    [tolgraven.chat.events]
    [tolgraven.doc-fx]
    [tolgraven.effects]
    [tolgraven.cofx :as cofx]
    [clojure.string :as string]
    [clojure.edn :as edn]
    ; [muuntaja.core :as m]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))


; re-frisk occasionally throws 10MB long "trace while storing" errors so def dont try to display that shit.
; (rf/set-loggers!  {:warn  (fn [& args]
;                               (util/log :warning "Warning" (apply str args)))   
;                    :error   (fn [& args]
;                               (util/log :error "Error" (apply str args))) })


(rf/reg-event-fx :common/navigate   [(rf/inject-cofx :scroll-position)]
  (fn [{:as cofx :keys [db scroll-position]} [_ match]]
    (let [old-match (:common/route db)
          new-match (assoc match :controllers
                                 (rfc/apply-controllers (:controllers old-match) match))
          new-name (-> new-match :data :name)]
      {:db (-> db
               (assoc :common/route new-match)
               (assoc :common/route-last old-match)
               (assoc-in [:state :error-page] nil) ; reset 404 page in case was triggered
               (assoc-in [:state :swap] nil)  ; reset for new swap (:db not event cause want it instant)
               (assoc-in [:state :scroll-position (-> old-match :path)] scroll-position)) ; yields a silly nilly slask men vafan no biggie
       :dispatch-n [[:exception [:page] nil]] ; reset exception state since using same error boundary for all pages
       :dispatch-later {:ms 100 ; should ofc rather queue up to fire on full page (size) load... something-Observer I guess
                        :dispatch (if-let [saved-pos (get-in db [:state :scroll-position (-> new-match :path)])]
                                    [:scroll/px saved-pos]
                                    [:scroll/to "linktotop"])} ; TODO use localstorage so restores on return visit halfway if someone follows link from blog etc..
       :document/set-title (->> new-match :data :name ;TODO want further info in title, like blog post title...
                                name string/capitalize
                                (str (get-in db [:content :title] "tolgrAVen") " - "))})))

(rf/reg-fx :common/navigate-fx!
  (fn [[k & [params query]]]
    (rfe/push-state k params query)))

(rf/reg-event-fx :common/navigate!
  (fn [_ [_ url-key params query]]
    {:common/navigate-fx! [url-key params query]}))


(rf/reg-event-fx :swap/trigger
  (fn [{:keys [db]} [_ item]]
    {:db (assoc-in db [:state :swap :running] item)
     :dispatch-later {:ms 1000 ; got transition taking 1s yet this (?) sometimes triggers abruptly before it ends hmm (+ hardly optimal so long...)
                      :dispatch [:swap/finish item]}}))

(rf/reg-event-fx :swap/finish
  (fn [{:keys [db]} [_ item]]
    {:db (-> db (assoc-in [:state :swap :finished] item)) }))


(rf/reg-event-fx :carousel/rotate
  (fn [{:keys [db]} [_ id curr-idx direction]]
    {:dispatch-n [[:carousel/set-direction id (case direction
                                                :dec "from-right"
                                                :inc "from-left")]
                  #_[:carousel/request-index id (case direction
                                                :dec (if (neg? (dec curr-idx))
                                                       (dec (count content))
                                                       (dec %)))]] ; XXX should defer send later tho
     :dispatch-later {:ms 25
                      :dispatch [:carousel/set-direction id nil]}}))

(rf/reg-event-db :carousel/set-direction
  (fn [db [_ id direction-class]]
    (assoc-in db [:state :carousel id :direction] direction-class)))

(rf/reg-event-db :carousel/set-index
  (fn [db [_ id idx]]
    (assoc-in db [:state :carousel id :index] idx)))

(rf/reg-event-fx :carousel/request-index
  (fn [{:keys [db]} [_ id direction]]
    {:dispatch-later {:ms 500
                      :dispatch [:carousel/set-index id direction]}}))


(defn assoc-in-factory [base-path]
  (fn [db [_ path value]]
    (assoc-in db (into base-path path) value)))

(rf/reg-event-db :content
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
   {:dispatch [:diag/new :debug "JS" "Reloaded"]}))


(rf/reg-event-fx :to-db
  (fn [db [_ path value]]
    (assoc-in db path value)))

; renamed store-> not fire->, should work to hide fire behind stuff so can swap out easier
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

(rf/reg-event-fx :<-store ; event version of <-store takes an on-success cb event
  (fn [_ [_ path on-success]]
    (let [kind (if (even? (count path))
                 :path-document
                 :path-collection)]
      {:firestore/get {kind path
                       :expose-objects true
                       :on-success on-success}})))


(rf/reg-event-fx :fb/fetch-settings [debug]
  (fn [{:keys [db]} _]
    {:dispatch [:http/get-internal {:uri "/api/firebase-settings"}
                [:fb/init]]}))

(rf/reg-event-fx :fb/error
  (fn [{:keys [db]} [_ error]]
    {:dispatch [:diag/new :error "Server error" error]}))

(rf/reg-event-fx :fb/init
  (fn [{:keys [db]} [_ data]]
    (firebase/init :firebase-app-info      data
                   :firestore-settings     @(rf/subscribe [:option [:firebase :settings]]) ; Shouldn't be used on later versions. See: https://firebase.google.com/docs/reference/js/firebase.firestore.Settings
                   :get-user-sub           [:fb/get-user]
                   :set-user-event         [:fb/set-user]
                   :default-error-handler  [:fb/error])
    {:db (assoc-in db [:state :firebase-initialized] true)
     :dispatch-n [[:init]
                  [:fb/fetch-users]]}))


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
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:state :is-personal] true) }))

(rf/reg-event-fx :appear
  (fn [{:keys [db]} [_ id value]]  ; would just set something in state that then sets css class.
    {:db (if value
           (assoc-in db [:state :appear id] value)
           (update-in db [:state :appear] dissoc id))})) ; now just generic


(rf/reg-event-fx :menu ;; this why better sep. can then inject css var and not sub? i somehow remeber that being badd
  (fn [{:as cofx :keys [db]} [_ state]]
    (let [open-height   @(rf/subscribe [:get-css-var "header-with-menu-height"])
          closed-height @(rf/subscribe [:get-css-var "header-height"]) ;TODO should rather be set from here with data-attr? ideally depends on content
          difference (->> (map js/parseFloat [open-height closed-height])
                          (apply -)
                          (* 0.5))]
      {:db (assoc-in db [:state :menu] state)
       :dispatch-n
        [[:->css-var! "header-height-current"
                      (if state open-height closed-height)]]
        :dispatch-later {:ms 250
                         :dispatch [:scroll/by (cond-> difference state -)]}}))) ;;haha silly.
;; XXX otherwise will have to uh, read var best we can and dispatch scroll event?


(rf/reg-event-fx :id-counters/handle
  (fn [{:keys [db]} [_ state]]
    {:dispatch [:diag/new :debug "ID-counters" (str "Restored to " state)]
     :id-counters/set! state}))

(rf/reg-event-fx :id-counters/fetch
  (fn [{:keys [db]} [_ _]]
    {:firebase/read-once {:path [:id-counters]
                          :on-success [:id-counters/handle]}}))

(rf/reg-event-db :loading/on ;; TODO should queue up a (cancelable) timeout event that will trigger unless category confirmed loading finished
 (fn [db [_ category]]
   (assoc-in db [:state :is-loading category] true)))
(rf/reg-event-db :loading/off
 (fn [db [_ category]]
   (assoc-in db [:state :is-loading category] false)))

(rf/reg-event-db :booted
 (fn [db [_ page]]
   (assoc-in db [:state :booted page] true)))


(localstore/reg-co-fx! :state       ;; local storage key
                       {:fx   :ls   ;; re-frame fx ID.     Both :fx and :cofx keys are optional,
                        :cofx :ls}) ;; re-frame cofx ID.   They will not be registered if unspecified.

(rf/reg-event-fx :ls/store-path   [(rf/inject-cofx :ls)]
 (fn [{:keys [db ls]} [_ ls-path db-path]] ;map of keys to paths I guess?
   {:ls (assoc-in ls ls-path (get-in db db-path))}))

(rf/reg-event-fx :ls/store-val    [(rf/inject-cofx :ls)]
 (fn [{:keys [ls]} [_ ls-path v]]
   {:ls (assoc-in ls ls-path v)}))

(rf/reg-event-fx :ls/get-path   [(rf/inject-cofx :ls)]
 (fn [{:keys [db ls]} [_ ls-path db-path]] ;map of keys to paths I guess?
   {:db (assoc-in db db-path (get-in ls ls-path)) }))


(rf/reg-event-fx :listener/add! 
 (fn [{:keys [db]} [_ el event f]]
   {:listener/add-fx [el event f]}))

(rf/reg-fx :listener/add-fx 
 (fn [[el event f]]
   (let [el (case el
              "document" js/document
              "window" js/window
              el)]
     (.addEventListener el event f))))

(rf/reg-event-fx :scroll/direction
 (fn [{:keys [db]} [_ direction position accum]]
   (let [header-height (if (get-in db [:state :menu])
                            @(rf/subscribe [:get-css-var "header-with-menu-height"])
                            @(rf/subscribe [:get-css-var "header-height"]))
         footer-height @(rf/subscribe [:get-css-var "footer-height"])
         trigger? (>= accum 0.01)
         far-enough? (>= position 0.10)]
    {:db (assoc-in db [:state :scroll-direction] direction)
     :dispatch-n [(when trigger? ;(and (>= accum 0.01) #_(>= position 0.10))
                    [:->css-var! "header-height-current" ;prob more reasonable offset :top yea?
                    (case direction
                      :down (if far-enough?
                              "0rem"
                              header-height)
                      :up header-height)])
                      (when trigger?
                        [:->css-var! "footer-bottom" ; not getting anim on height somehow...
                         (case direction
                           :down (if far-enough?
                                   footer-height
                                   "0rem")
                           :up "0rem")])
                 [:state [:scroll-direction] direction] ]})))


(rf/reg-event-fx :listener/scroll-direction ;but to fully emulate safari for hiding would need certain amounts of fires in same dir in x amount of time etc
 (fn [{:keys [db]} [_ _]]
   (let [scroll-pos (atom 0)
         last-direction (atom :up)
         last-page-size (atom (.-height (.getBoundingClientRect js/document.body)))
         ; last-invoked (atom (js/Date.now))
         accum-in-direction (atom 0)
         callback (fn [e]
                    (when true #_(< 100 (- (js/Date.now) @last-invoked))
                      ; (reset! last-invoked (js/Date.now))
                      (let [height (.-height (.getBoundingClientRect js/document.body))
                            new-pos (-> (.-scrollY js/window)
                                        (/ height)
                                        util/format-number)
                            new-direction (cond
                                           (> new-pos @scroll-pos) :down
                                           (< new-pos @scroll-pos) :up
                                           :else @last-direction)]
                        (when (and (not= @scroll-pos new-pos) ;tho fraction gets silly should be absolute/px i guess
                                   (= @last-page-size height)) ;avoid faux scroll on page resize.
                          (reset! accum-in-direction (if (= new-direction @last-direction)
                                                       (+ @accum-in-direction (util/abs (- new-pos @scroll-pos)))
                                                       0))
                          (reset! last-direction new-direction)
                          (reset! scroll-pos new-pos)
                          (when (<= 0.05 @accum-in-direction)
                            (rf/dispatch [:scroll/direction
                                          @last-direction @scroll-pos @accum-in-direction])))
                        (reset! last-page-size height))))]
     {:dispatch [:listener/add! "document" "scroll" callback]})))
 

(rf/reg-event-fx :listener/before-unload-save-scroll ; gets called even when link to save page, silly results.
 (fn [{:keys [db]} [_ ]]
  (let [scroll-to-ls (fn []
                       (rf/dispatch-sync [:state [:scroll-position (-> db :common/route :data :name)]
                                          (.-scrollY js/window)])
                       (rf/dispatch-sync [:ls/store-path [:scroll-position]
                                                         [:state :scroll-position]]))] ; im sure we'll want more tho?
    {:dispatch-n [[:listener/add! "window" "beforeunload" scroll-to-ls]]})))


(rf/reg-event-fx :init/scroll-storage  [(rf/inject-cofx :ls)] ;fetch any existing values, setup listener to persist...
  (fn [{:keys [db ls]} _]
    {:db (assoc-in db [:state :scroll-position] (:scroll-position ls))
     :dispatch [:listener/before-unload-save-scroll]}))


(rf/reg-event-fx :init ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [{:keys [db]} [_ _]]
  {:dispatch-n [[:init/scroll-storage]
                ; [:listener/scroll-direction]
                [:id-counters/fetch]
                [:strava/init]
                [:instagram/init]
                [:page/init-blog]]}))

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
    (let [cleanup [:loading/off kind]] ; set something to indicate request is underway
      {:dispatch [:loading/on kind]   ;; tho want this per-request so figure out. by passing path frag maybe... slightly better now at least
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

(rf/reg-event-fx :http/get-internal
  (get-http-fn :get))

(rf/reg-event-fx :http/get
  (get-http-fn :get
               {:response-format (ajax/json-response-format {:keywords? true})}))

(rf/reg-event-fx :http/post [debug]
  (get-http-fn :post
               {:format (ajax/json-request-format)}))

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


(rf/reg-event-fx :modal-zoom
 (fn [{:keys [db]} [_ id action item]]
  (case action
    :close {:db (update-in db [:state :modal-zoom id] dissoc :opened)
            :dispatch-later {:ms 500
                             :dispatch [:modal-zoom id :closed]}}
    :closed {:db (update-in db [:state :modal-zoom] dissoc id)}
    :open {:db (-> db
                   (assoc-in [:state :modal-zoom id :component] item)
                   (assoc-in [:state :modal-zoom id :opened] true))}
    :loaded {:db (assoc-in db [:state :modal-zoom id :loaded] true)})))

(rf/reg-event-fx
 :github/fetch-commits
 (fn [{:keys [db]} [_ _]]
   (when-not (get-in db [:content :github :repo])
     {:dispatch [:http/get {:uri "https://api.github.com/repos/tolgraven/tolgraven/commits"
                            :headers {"Accept" "application/vnd.github.v3+json"}}
                 [:content [:github :repo]]]})))

(rf/reg-event-fx :text-effect-char-by-char/start
 (fn [{:keys [db]} [_ path text ms]]
   (when-not (get-in db path)
     {:db (assoc-in db path {:text-full text
                             :text-out ""})
    :dispatch-later {:ms ms
                     :dispatch [:text-effect-char-by-char/tick path text 0 ms]}})))

(rf/reg-event-fx :text-effect-char-by-char/tick
 (fn [{:keys [db]} [_ path text num-chars ms]]
   (when-not (= text (:text-out (get-in db path)))
     (let [text-out (apply str (take (inc num-chars) (seq text)))]
       {:db (update-in db path merge {:text-out text-out})
        :dispatch-later {:ms ms
                         :dispatch [:text-effect-char-by-char/tick path text (inc num-chars) ms]}}))))
