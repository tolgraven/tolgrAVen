(ns tolgraven.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [re-frame.std-interceptors :as interceptor]
    [ajax.core :as ajax]
    [day8.re-frame.http-fx]
    [com.degel.re-frame-firebase :as firebase]
    [district0x.re-frame.google-analytics-fx]
    ; [day8.re-frame.tracing :refer-macros [fn-traced]]
    [day8.re-frame.async-flow-fx :as async-flow-fx]
    [akiroz.re-frame.storage :as localstore]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [re-pollsive.core :as poll]
    [tolgraven.util :as util]
    [tolgraven.blog.events]
    [tolgraven.user.events]
    [tolgraven.strava.events]
    [tolgraven.instagram.events]
    [tolgraven.chat.events]
    [tolgraven.github.events]
    [tolgraven.listener]
    [tolgraven.scroll]
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
       (when (or (and (same :query-params) ; TODO maybe query params did change but also something else tho
                      (or (not (same :data :view))
                          (not (same :path-params))))
                 (not old-match)) ; restore last position if followed a link from elsewhere (even if go to top for internal links)
        {:dispatch-later
         {:ms 250
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

(rf/reg-event-fx :href/update-current
  (fn [{:keys [db]} [_ {:keys [path query]}]]
    (let [k (get-in db [:common/route :data :name])
          p (get-in db [:common/route :parameters :path])
          q (get-in db [:common/route :parameters :query])]
      {:dispatch
       [:common/navigate! k (merge p path) (merge q query)]})))


(rf/reg-event-fx :history/popped
  (fn [{:keys [db]} [_ e]]
    (let [nav-action? true  #_(.-isNavigation e)] ; by fact we getting the event heh
      (if nav-action?  ; bona fide back (or fwd??) event! ; set a flag affecting next common/navigate event
        {:db (assoc-in db [:state :browser-nav :got-nav] true)}))))

(rf/reg-event-fx :history/pop!
  (fn [{:keys [db]} [_ _]]
    {:history/pop _}))
(rf/reg-fx :history/pop
  (fn [_]
    (.back js/window.history)))


(rf/reg-event-fx :swap/trigger
  (fn [{:keys [db]} [_ item]]
    {:db (assoc-in db [:state :swap :running] item)
     :dispatch-later {:ms 1000 ; got transition taking 1s yet this (?) sometimes triggers abruptly before it ends hmm (+ hardly optimal so long...)
                      :dispatch [:swap/finish item]}}))

(rf/reg-event-fx :swap/finish
  (fn [{:keys [db]} [_ item]]
    {:db (-> db (assoc-in [:state :swap :finished] item)
                (update-in [:state :swap] dissoc :running)) }))



(rf/reg-event-fx :dispatch-in/set      [(rf/inject-cofx :gen-id [:dispatch-later])
                                        (rf/inject-cofx :now-ct)]
  (fn [{:keys [db id now-ct]} [_ {:keys [k ms dispatch dispatch-n]}]]
    (let [id (get-in id [:id :dispatch-later])
          events (into dispatch-n dispatch)]
      {:db {assoc-in db [:state :dispatch-in k id] {:ms ms
                                                    :elapsed 0
                                                    :events events
                                                    :started now-ct               
                                                    :js-id nil}} ;not yet running
       :dispatch-in/set [k id ms events]})))

(rf/reg-event-fx :dispatch-in/save-timeout-id
  (fn [{:keys [db]} [_ [k id js-id]]]
    {:db (assoc-in db [:state :dispatch-in k id :js-id] js-id)}))

(rf/reg-event-fx
 :dispatch-in/cancel      [(interceptor/path [:state :dispatch-in])]
 (fn [{:keys [db]} [_ [k & [id]]]]
   (let [ids (or id (-> db k keys))]
     (if (= 1 (count ids))
       (let [js-id (get-in db [k id :js-id])]
         {:db (dissoc db k)
          :dispatch-in/stop [k id js-id]})
       {:dispatch-n (for [single-id ids]
                      [:dispatch-in/cancel k single-id])}))))

(rf/reg-event-fx
 :dispatch-in/pause     [(rf/inject-cofx :now-ct)
                         (interceptor/path [:state :dispatch-in])]
 (fn [{:keys [db now-ct]} [_ [k & [id]]]]
   (let [id (or id (-> db
                       (select-keys k)
                       key))
         js-id (get-in db [k id :js-id])]
     {:db (-> db
              (assoc-in [k id :js-id] nil)
              (assoc-in [k id :elapsed]
                        (- now-ct (get-in db [k id :started]))))
      :dispatch-in/stop [k id js-id]})))

(rf/reg-event-fx
 :dispatch-in/resume      [(interceptor/path [:state :dispatch-in])]
 (fn [{:keys [db]} [_ [k & [id]]]]
   (let [id (or id (-> db
                       (select-keys k)
                       key))
         {:keys [ms elapsed events]} (get-in db [k id])
         ms (- ms elapsed)]
     {:db (assoc-in db [k :ms] ms)
      :dispatch-in/set [k id ms events]})))

#_(rf/reg-event-fx :dispatch-in/ignore ; keep em running just ignore them. useful?
  (fn [{:keys [db]} [_ [k id]]]
    {:db nil }))

(rf/reg-fx :dispatch-in/set
  (fn [[k id ms events]]
    (let [js-id (js/setTimeout #(doseq [event events]
                                  (rf/dispatch event))
                               ms)]
     (rf/dispatch [:dispatch-in/save-timeout-id k id js-id]))))

(rf/reg-fx :dispatch-in/stop
  (fn [[k id js-id]]
    (js/clearTimeout js-id)))



(rf/reg-event-fx :carousel/rotate
  (fn [{:keys [db]} [_ id content direction]]
    (let [curr-idx (get-in db [:state :carousel id :index])]
      {:dispatch [:carousel/set-index id (case direction
                                                  :dec (if (neg? (dec curr-idx))
                                                         (dec (count content))
                                                         (dec curr-idx))
                                                  :inc (if (< (inc curr-idx) (count content))
                                                         (inc curr-idx)
                                                         0))]})))

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

(rf/reg-event-fx :contact/send-request
 (fn [{:keys [db]} [_ _]]
   (let [{:keys [name email title message]} (get-in db [:state :form-field :contact])]
     {:dispatch-n [[:http/post {:uri "https://script.google.com/macros/s/AKfycbxGp49fa2hLZSKWwYQs6KxzZPzgnACUMvHzPLungNMb3Y6WzTq2qDyWGEy06202-hU/exec"
                                :body (util/m->json {:name name :email email :title title :message message})
                                :redirect "follow"
                                :response-format (ajax/json-response-format {:keywords? true})}
                    [:contact/request-sent]]]})))

(rf/reg-event-fx :contact/request-sent
  (fn [{:keys [db]} [_ response]]
    {:db (-> db
             (assoc-in [:state :contact-form :sent?] true)
             (assoc-in [:state :contact-form :response] response))
     :dispatch-n [[:form-field [:contact-form :message] nil]
                  [:form-field [:contact-form :title] nil]]}))

(rf/reg-event-fx :contact/open
  (fn [{:keys [db]} [_]]
    {:db (assoc-in db [:state :contact-form :show?] true)}))

(rf/reg-event-fx :contact/close
  (fn [{:keys [db]} [_ force?]]
    (if force?
      {:db (update-in db [:state :contact-form] merge
                      {:show? false :sent? false :closing? false})}
      {:db (assoc-in db [:state :contact-form :closing?] true)
       :dispatch-later {:ms 1000
                        :dispatch [:contact/close :force]}})))



(defn assoc-in-factory [base-path]
  (fn [db [_ path value]]
    (assoc-in db (into base-path path) value)))

(rf/reg-event-db :content (assoc-in-factory [:content]))
(rf/reg-event-db :state   (assoc-in-factory [:state]))
(rf/reg-event-db :option  (assoc-in-factory [:option]))

(rf/reg-event-fx :debug ;[debug]
  (fn [{:keys [db]} [_ path value]]
    (case path
      [:layers] (-> (js/document.querySelector "main") ; should be fx no?
                    .-classList
                    (.toggle "debug-layers")))
    {:db (assoc-in db (into [:state :debug] path) value)}))

(rf/reg-event-db :exception (assoc-in-factory [:state :exception]))

(rf/reg-event-fx :form-field []
  (fn [{:keys [db]} [_ path value]]
    {:db (assoc-in db (into [:state :form-field] path) value)
     :dispatch [:ls/store-val (into [:form-field] path) value]}))


(rf/reg-event-fx :reloaded
 (fn [db [_ _]]
   {:dispatch-n [[:exception nil]
                 [:diag/new :debug "JS" "Reloaded"]]}))

(rf/reg-event-fx :toggle-class!
 (fn [db [_ id class]]
   {:toggle-class [id class]}))
(rf/reg-fx :toggle-class
  (fn [[id class]]
    (util/toggle-class id class)))

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

; TODO surely should wrap on-success to strip metadata etc and just store keywordized :data directly here
; with util/normalize-firestore. just need to get rid of that from elsewhere then tho
(rf/reg-event-fx :<-store ; event version of <-store takes an on-success cb event
  (fn [_ [_ path on-success on-failure]]
    (let [kind (if (even? (count path))
                 :path-document
                 :path-collection)]
      {:firestore/get (merge {kind path
                              :expose-objects true
                              :on-success [:store/on-success on-success]} ;TODO mod firestore lib to accept vectors/wrapping. goddamn
                             (when on-failure
                               {:on-failure on-failure}))})))

(rf/reg-event-fx :store/on-success ; strip metadata etc
  (fn [_ [_ on-success data]]
    {:dispatch (conj on-success (util/normalize-firestore-general data))}))

(rf/reg-event-fx :store/on-success-wrapper
  (fn [_ [_ ]]))

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
    (firebase/init :firebase-app-info      data ; well should go in fx tho...
                   :firestore-settings     (get-in db [:options :firebase :settings]) ; Shouldn't be used on later versions. See: https://firebase.google.com/docs/reference/js/firebase.firestore.Settings
                   :get-user-sub           [:fb/get-user]
                   :set-user-event         [:fb/set-user]
                   :default-error-handler  [:fb/error])
    {:dispatch-n [[:booted :firebase]
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

(rf/reg-event-fx :page/init-home ;[debug] ; really should do the fetch from wherever it is content eventually comes from...
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:state :is-personal] false)}))

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

(rf/reg-event-fx :id-counters/fetch ; obviously needs to be a sub-sub and put in views only, since otherwise concurrent usage would cause clashes hehe. tho better to have server gen these anyways.
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

(rf/reg-event-fx :ls/dissoc       [(rf/inject-cofx :ls)] ; keep localstorage reasonably clean... doesn't appear to work!!
 (fn [{:keys [ls]} [_ ls-path]]
   {:ls (update-in ls (butlast ls-path) dissoc (last ls-path))})) ; investigate why won't take.

(rf/reg-event-fx :ls/get-path   [(rf/inject-cofx :ls)]
 (fn [{:keys [db ls]} [_ ls-path db-path]] ;map of keys to paths I guess?
   {:db (update-in db db-path merge (get-in ls ls-path)) }))

(rf/reg-event-fx :cookie/show-notice   [(rf/inject-cofx :ls)]
 (fn [{:keys [db ls]} [_ ]] ;map of keys to paths I guess?
   (let [id :cookie-notice]
     (when-not (get-in ls [:cookie-notice-accepted])
       {:dispatch [:diag/new :info "Cookie notice"
                   {:what "This website may use cookies"
                    :why "To track whether you've agreed to the use of cookies"
                    :how {:by-closing-or-pressing-ok "You agree to not only cookies"
                          :but "also milk"}}
                   {:sticky? true
                    :custom-id id
                    :actions [[:cookie/accept-notice true]]
                    :buttons [{:id :accept
                               :text "OK"
                               :action [:cookie/accept-notice true]}
                              {:id :decline
                               :text "No, never!"
                               :action [:cookie/accept-notice false]}]}]}))))

(rf/reg-event-fx :cookie/accept-notice
 (fn [{:keys [db]} [_ accepted?]]
   {:dispatch-n [[:diag/unhandled :remove :cookie-notice]
                 (when accepted?
                   [:ls/store-val [:cookie-notice-accepted] true])]}))

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

(rf/reg-event-fx :init/scroll-storage  [(rf/inject-cofx :ls)] ;fetch any existing values, setup listener to persist...
  (fn [{:keys [db ls]} _]
    {:db (assoc-in db [:state :scroll-position] (:scroll-position ls))
     :dispatch [:listener/before-unload-save-scroll]}))

(rf/reg-event-fx :init  [] ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [{:keys [db]} [_ _]]
  {:dispatch-n [[:listener/load]
                [:init/scroll-storage]
                [:listener/popstate-back]
                [:listener/scroll-direction]
                [:on-booted :firebase [:id-counters/fetch]]
                [:ls/get-path [:form-field] [:state :form-field]] ; restore any active form-fields
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
 (fn [{:keys [db now id]} [_ level title message {:keys [sticky? actions buttons custom-id]}]] ;error, warning, info
  (let [id (or custom-id (-> id :id :diag))]
    (merge
     {:db (update-in db [:diagnostics :messages]
                     assoc id {:level   level
                               :id      id
                               :title   title
                               :message message
                               :time    now
                               :buttons buttons
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



(rf/reg-event-fx :text-effect-char-by-char/start ; this is super dumb plus obviously didnt work so well. keep things local unless necessary dammit
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

(rf/reg-event-fx :darken/but-element
 (fn [{:keys [db]} [_ id-or-class timeout]]
   {:db (assoc-in db [:state :darken-but] id-or-class)
    :dispatch-n [[:toggle-class! id-or-class "darken-fadeout-restore"]
                 [:toggle-class! nil "darken-fadeout"]]
    :dispatch-later {:ms timeout
                     :dispatch [:darken/restore id-or-class]}}))

(rf/reg-event-fx :darken/restore
 (fn [{:keys [db]} [_ id-or-class]]
   {:db (update-in db [:state] dissoc :darken-but)
    :dispatch-n [[:toggle-class! id-or-class "darken-fadeout-restore"]
                 [:toggle-class! nil "darken-fadeout"]]}))
