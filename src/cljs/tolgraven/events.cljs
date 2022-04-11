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
          same (fn [& path]
                 (= (get-in new-match path)
                    (get-in old-match path)))]
      (if-not (and (same :data :view)
                   (same :path-params)
                   (same :query-params)
                   (same :path))
      (merge
       {:db (-> db
               (assoc :common/route new-match)
               (assoc :common/route-last old-match)
               (update-in [:state] dissoc :error-page) ; reset 404 page in case was triggered
               ; (update-in [:state] dissoc :swap)  ; cant reset swap since in middle of running...
               (update-in [:state :exception] dissoc :page)
               (assoc-in [:state :scroll-position (-> old-match :path)] scroll-position))
       :document/set-title (str (get-in db [:content :title] "tolgrAVen") " - "
                                (-> new-match :data :name ;TODO want further info in title, like blog post title...
                                    name string/capitalize) " "
                                (-> new-match :parameters :path vals first))}
       (when (and (same :query-params) ; TODO maybe query params did change but also something else tho
                  (or (not (same :data :view))
                      (not (same :path-params))))
        {:dispatch-later
         {:ms 150
          :dispatch [:scroll/on-navigate (:path new-match)]}}))
      
      (let [fragment (-> db :state :fragment)] ;; matches are equal (fragment not part of match)
        (if (pos? (count (seq fragment))) 
          {:db (update-in db [:state] dissoc :fragment)
           :dispatch-later {:ms 150 ; obv too much. but maybe scroll issues partly from swapper bs?
                            :dispatch [:scroll/to fragment]}}))))))


(rf/reg-fx :common/navigate-fx!
  (fn [[k & [params query]]]
    (rfe/push-state k params query)))

(rf/reg-event-fx :common/navigate!
  (fn [_ [_ url-key params query]]
    {:common/navigate-fx! [url-key params query]}))

(rf/reg-event-fx :history/popped
  (fn [{:keys [db]} [_ e]]
    (let [nav-action? true  #_(.-isNavigation e)] ; by fact we getting the event heh
      (if nav-action?  ; bona fide back (or fwd??) event! ; set a flag affecting next common/navigate event
        {:db (assoc-in db [:state :browser-nav :got-nav] true)}))))

(rf/reg-event-fx :history/pop!
  (fn [{:keys [db]} [_ _]]
    (.back js/window.history)))

(rf/reg-event-fx :scroll/on-navigate ; TODO detect fresh/reload and treat it as browser nav so dont lose position on page...
  (fn [{:keys [db]} [_ path]]        ; TODO !! on iphone (also mac safari?) cancel transition on browser nav! fugly
    (let [browser-nav? (get-in db [:state :browser-nav :got-nav])
          saved-pos (get-in db [:state :scroll-position path])]
      {;:db (update-in db [:state :browser-nav] dissoc :got-nav) ;clear any browser nav flag since we consume it
       :dispatch (if browser-nav? ; used back/fwd, since clicking a link should equal there or top...
                   [:scroll/and-block (or saved-pos "main")] ; guess should check for frag, query etc tho
                   [:scroll/and-block "main"])
       :dispatch-later {:ms 300 ; should ofc rather queue up to fire on full page (size) load... something-Observer I guess
                        :dispatch [:state [:browser-nav :got-nav] false]} }))) ; waiting because checks in main-page


(rf/reg-event-fx :scroll/to-top-and-arm-restore ; when follow links etc want to get to top of new page. but if have visited page earlier, offer to restore latest view pos. ACE!!!
  (fn [{:keys [db]} [_ path saved-pos]]
    {;:db (assoc-in db [:state :scrolling-to-top] true)
     :dispatch-n [[:scroll/to "linktotop"]
                  ; [:diag-could-work-in-context-of-long-articles-etc] ;but too annoying on most all navs
                  ;; diag first time it happens to point out new button next to "to top" button
                  ;; which will show when available
                  ;; beauty is with ls it'll work across reloads etc as well
                  ;; could also expose as pref whether to force old more (crappy) appy behavior
                  ]
     :dispatch-later {:ms 10
                      :dispatch [:scroll/set-block true]}}))

(rf/reg-event-fx :scroll/and-block ; when follow links etc want to get to top of new page. but if have visited page earlier, offer to restore latest view pos. ACE!!!
  (fn [{:keys [db]} [_ px-or-elem-id]]
    {:dispatch (if (number? px-or-elem-id)
                 [:scroll/px px-or-elem-id]
                 [:scroll/to px-or-elem-id])
     :dispatch-later {:ms 1
                      :dispatch [:scroll/set-block true]}}))

(rf/reg-event-fx :scroll/set-block
  (fn [{:keys [db]} [_ is?]]
    (merge
     {:db (assoc-in db [:state :scroll :block] is?)}
     (when is?
       {:dispatch-later {:ms 1000
                         :dispatch [:scroll/set-block false]}}))))


(rf/reg-event-fx :swap/trigger
  (fn [{:keys [db]} [_ item]]
    {:db (assoc-in db [:state :swap :running] item)
     :dispatch-later {:ms 1000 ; got transition taking 1s yet this (?) sometimes triggers abruptly before it ends hmm (+ hardly optimal so long...)
                      :dispatch [:swap/finish item]}}))

(rf/reg-event-fx :swap/finish
  (fn [{:keys [db]} [_ item]]
    {:db (-> db (assoc-in [:state :swap :finished] item)
                (update-in [:state :swap] dissoc :running)) }))


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

(rf/reg-event-db :content (assoc-in-factory [:content]))
(rf/reg-event-db :state   (assoc-in-factory [:state]))
(rf/reg-event-db :option  (assoc-in-factory [:option]))

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
                [:fb/init]
                [:fb/error]]}))

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
         [:http/get {:uri             "/api/docs"
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


(rf/reg-event-fx :menu    [(rf/inject-cofx :css-var [:header-with-menu-height])
                           (rf/inject-cofx :css-var [:header-height])]
  (fn [{:as cofx :keys [db css-var]} [_ state]]
    (let [open-height   (:header-with-menu-height css-var)
          closed-height (:header-height css-var) ;TODO should rather be set from here with data-attr? ideally depends on content
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
 (fn [db [_ category id]]
   (update-in db [:state :is-loading category] (comp set conj) (or id :default))))
(rf/reg-event-db :loading/off ;; TODO also gen unique ID so can have nultiple loads same cat not interfering
 (fn [db [_ category id]]
   (update-in db [:state :is-loading category] (comp set disj) (or id :default))))

(rf/reg-event-fx :on-booted ; queue event up to fire once init complete
  (fn [{:keys [db]} [_ id event]]
    (if (get-in db [:state :booted id])
      {:dispatch event} ; just send it if already booted
      {:db (update-in db [:state :on-booted id] (comp set conj) event)})))

(rf/reg-event-fx :booted
 (fn [{:keys [db]} [_ id]]
   {:db (-> db
            (assoc-in [:state :booted id] true)
            (update-in [:state :on-booted] dissoc id))
    :dispatch-n (vec (get-in db [:state :on-booted id]))}))



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


(rf/reg-event-fx :scroll/direction    [(rf/inject-cofx :css-var [:header-with-menu-height])
                                       (rf/inject-cofx :css-var [:header-height])
                                       (rf/inject-cofx :css-var [:footer-height])
                                       (rf/inject-cofx :css-var [:space-top])
                                       (rf/inject-cofx :css-var [:footer-height-current])]
 (fn [{:keys [db css-var]} [_ direction position height at-bottom?]]
   (let [header-height (if (get-in db [:state :menu])
                            (:header-with-menu-height css-var)
                            (:header-height css-var))
         past-top? (>= position (+ (util/rem-to-px (:header-height css-var))     ; distance from top to main is header-height + space-top above/below,
                                   (* 2 (util/rem-to-px (:space-top css-var))))) ; + space-lg above main. but header + 2x space-top seems sufficient...
         hidden? (get-in db [:state :hidden-header-footer])
         at-bottom? (>= position
                        (- height
                           (.-innerHeight js/window)
                           50
                           (util/rem-to-px (:footer-height-current css-var))))] ; will jump page so...
    {:dispatch-n [(if (or (and hidden?
                               (= direction :up))
                          at-bottom?)
                   [:hide-header-footer false]
                   (when (and (not hidden?)
                              (= direction :down)
                              past-top?
                              (not (get-in db [:state :menu])))
                     [:hide-header-footer true]))
                  (if (and at-bottom? (not (get-in db [:state :scroll :at-bottom])))
                    [:scroll/at-bottom true]
                    (when (and (not at-bottom?) (get-in db [:state :scroll :at-bottom]))
                      [:scroll/at-bottom false]))
                  (if (and past-top? (not (get-in db [:state :scroll :past-top])))
                    [:scroll/past-top true]
                    (when (and (not past-top?) (get-in db [:state :scroll :past-top]))
                      [:scroll/past-top false]))]})))

(rf/reg-event-fx :scroll/past-top
 (fn [{:keys [db]} [_ past-top?]]
   {:db (assoc-in db [:state :scroll :past-top] past-top?) }))

(rf/reg-event-fx :scroll/at-bottom    [(rf/inject-cofx :css-var [:footer-height])
                                       (rf/inject-cofx :css-var [:footer-height-full])]
 (fn [{:keys [db css-var]} [_ bottom?]]
   (merge
    {:db (assoc-in db [:state :scroll :at-bottom] bottom?)
     :dispatch-n [[:->css-var! "footer-height-current"
                   (if (and bottom?) ; "is on front page", trickier than first might think due to idiosyncraticlol design choices
                     (:footer-height-full css-var)
                     (if (get-in db [:state :hidden-header-footer])
                       "0rem"
                       (:footer-height css-var)))]]}
   (when bottom?
     {:dispatch-later {:ms 300
                       :dispatch
                       [:scroll/by (js/parseFloat (:footer-height-full css-var))]}}))))


(rf/reg-event-fx :hide-header-footer  [(rf/inject-cofx :css-var [:header-with-menu-height])
                                       (rf/inject-cofx :css-var [:header-height])
                                       (rf/inject-cofx :css-var [:footer-height])]
 (fn [{:keys [db css-var]} [_ hide?]]
   (let [header-height (if hide?
                         "0rem"
                         (if (get-in db [:state :menu])
                            (:header-with-menu-height css-var)
                            (:header-height css-var)))
         footer-height (if hide?
                         "0rem"
                         (:footer-height css-var))]
     {:db (assoc-in db [:state :hidden-header-footer] hide?)
      :dispatch-n [[:->css-var! "header-height-current" header-height]
                   (when-not (get-in db [:state :scroll :at-bottom])
                     [:->css-var! "footer-height-current" footer-height])]})))

 ; this causes event spam obviously but since need subs & db it's necessary.
(rf/reg-event-fx :listener/scroll-direction   [(rf/inject-cofx :css-var [:header-height])
                                               (rf/inject-cofx :css-var [:space-top])]
 (fn [{:keys [db css-var]} [_ _]]
   (let [scroll-pos (atom 0)
         last-direction (atom :up)
         accum-in-direction (atom 0)
         get-height (fn []
                      (- (.-clientHeight (util/elem-by-id "main"))
                         (-> (util/elem-by-id "main")
                             js/getComputedStyle
                             .-paddingBottom
                             js/parseFloat)))
         page-height (atom (get-height))
         triggered-at (atom (ct/now))
         top-size (+ (util/rem-to-px (:header-height css-var))     ; distance from top to main is header-height + space-top above/below,
                     (* 2 (util/rem-to-px (:space-top css-var)))
                     50)
         callback (fn [e]
                    (let [new-pos (.-scrollY js/window)
                          new-height (get-height) ; jumps between actual and ~double val causing jitter and badness...
                          new-direction (cond
                                         (> new-pos @scroll-pos) :down
                                         (< new-pos @scroll-pos) :up
                                         :else @last-direction)
                          at-bottom? (>= new-pos ; XXX use calc from :scroll/direction instead...
                                         (- new-height
                                            (.-innerHeight js/window)
                                            500)) ; "maybe at bottom"
                          at-top? (<= @scroll-pos top-size)]
                        (when (and (not= @scroll-pos new-pos)
                                   (= @page-height new-height)) ; avoid running up accum from massive page size jumps...
                          (reset! accum-in-direction (if (= new-direction @last-direction)
                                                       (+ @accum-in-direction (util/abs (- new-pos @scroll-pos)))
                                                       0 #_(util/abs (- new-pos @scroll-pos))))
                          (reset! last-direction new-direction)
                          (reset! scroll-pos new-pos)
                          (when (and (or at-bottom?
                                         at-top?
                                         (ct/after? (ct/minus (ct/now) (ct/millis 250)) @triggered-at)) ; ensure "scroll" isn't due to content resizing
                                     (or (<= 250 @accum-in-direction) ; bit of debounce
                                          (and at-top?
                                               (= new-direction :up))
                                          (and at-bottom?
                                               (= new-direction :down)))) ; always post when at bottom, regardless of accum
                            (reset! accum-in-direction 0)
                            (reset! triggered-at (ct/now))
                            (rf/dispatch [:scroll/direction
                                          @last-direction @scroll-pos new-height at-bottom?])))
                        (reset! page-height new-height)))]
     {:dispatch [:listener/add! "document" "scroll" callback]})))

(rf/reg-event-fx :listener/before-unload-save-scroll ; gets called even when link to save page, silly results.
 (fn [{:keys [db]} [_ ]]
  (let [scroll-to-ls (fn []
                       (rf/dispatch-sync [:state [:scroll-position (-> db :common/route :data :name)]
                                          (.-scrollY js/window)])
                       (rf/dispatch-sync [:ls/store-path [:scroll-position]
                                                         [:state :scroll-position]]))] ; im sure we'll want more tho?
    {:dispatch-n [[:listener/add! "window" "beforeunload" scroll-to-ls]]})))

(rf/reg-event-fx :listener/popstate-back ; gets called on browser back. or if we nanually pop state, so keep track of that if end up doing it...
 (fn [{:keys [db]} [_ ]]
   (let [f (fn [e]
             (rf/dispatch-sync [:history/popped e]))] ; which will actually have fresh db and can do stuff ugh
    {:dispatch [:listener/add! "window" "popstate" f]})))

(rf/reg-event-fx :init/scroll-storage  [(rf/inject-cofx :ls)] ;fetch any existing values, setup listener to persist...
  (fn [{:keys [db ls]} _]
    {:db (assoc-in db [:state :scroll-position] (:scroll-position ls))
     :dispatch [:listener/before-unload-save-scroll]}))


(rf/reg-event-fx :init ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [{:keys [db]} [_ _]]
  {:dispatch-n [[:init/scroll-storage]
                [:listener/scroll-direction]
                [:id-counters/fetch]
                [:listener/popstate-back]
                [:booted :site]]})) ; should work, main page specific init events won't get queued unless on main so...

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



(rf/reg-event-fx :github/init
 (fn [{:keys [db]} [_ user repo]]
   (when-not (get-in db [:state :github :pages-fetched])
     {:dispatch-n [[:github/fetch-commits user repo 1]
                   [:github/fetch-commit-count user repo]]})))

(rf/reg-event-fx :github/fetch-commits
 (fn [{:keys [db]} [_ user repo page]]
   (let [url "https://api.github.com/repos/"]
     (when (and page
                (not (some #{page} (get-in db [:state :github :pages-fetched]))))
       {:dispatch-n [[:http/get {:uri (str url user "/" repo "/commits")
                                 :headers {"Accept" "application/vnd.github.v3+json"}
                                 :params {:page page}}
                      [:github/save-commits page]]]}))))

(rf/reg-event-fx :github/save-commits
 (fn [{:keys [db]} [_ page data]]
   {:db (-> db
            (update-in [:content :github :repo] concat data)
            (update-in [:state :github :pages-fetched] conj page))}))

(rf/reg-event-fx :github/fetch-commits-next
 (fn [{:keys [db]} [_ user repo]]
   (when-let [page (some->> (get-in db [:state :github :pages-fetched])
                            (apply max)
                            inc)]
     {:dispatch [:github/fetch-commits user repo page]})))

(rf/reg-event-fx :github/fetch-commit-count
 (fn [{:keys [db]} [_ user repo]]
   (let [url "https://api.github.com/repos/"]
     (when-not (get-in db [:content :github :repo-headers])
       {:dispatch [:http/get {:uri (str url user "/" repo "/commits")
                              :url-params {:per_page 1}
                              :response-format {:read #(js->clj (.getResponseHeaders %)
                                                                :keywordize-keys true)
                                                :description "headers"}
                              :headers {"Accept" "application/vnd.github.v3+json"}}
                   [:content [:github :repo-headers]] ]}))))


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
