(ns tolgraven.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [re-frame.std-interceptors :as interceptor]
    [ajax.core :as ajax]
    [day8.re-frame.http-fx]
    [com.degel.re-frame-firebase :as firebase]
    ; [day8.re-frame.tracing :refer-macros [fn-traced]]
    [day8.re-frame.async-flow-fx :as async-flow-fx]
    [akiroz.re-frame.storage :as localstore]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [re-pollsive.core :as poll]
    [breaking-point.core :as bp]
    [tolgraven.util :as util]
    [tolgraven.blog.events]
    [tolgraven.user.events]
    [tolgraven.strava.events]
    [tolgraven.instagram.events]
    [tolgraven.chat.events]
    [tolgraven.gpt.events]
    [tolgraven.github.events]
    [tolgraven.search.events]
    [tolgraven.docs.events]
    [tolgraven.listener]
    [tolgraven.scroll]
    [tolgraven.doc-fx]
    [tolgraven.effects]
    [tolgraven.cofx :as cofx]
    [clojure.string :as string]
    [clojure.edn :as edn]
    [goog.object :as gobj]
    ; [muuntaja.core :as m]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))


; re-frisk occasionally throws 10MB long "trace while storing" errors so def dont try to display that shit.
; (rf/set-loggers!  {:warn  (fn [& args]
;                               (util/log :warning "Warning" (apply str args)))   
;                    :error   (fn [& args]
;                               (util/log :error "Error" (apply str args))) })


(rf/reg-event-fx :common/navigate   [debug
                                     (rf/inject-cofx :scroll-position)
                                     (rf/inject-cofx :gen-id [:navigations])]
  (fn [{:as cofx :keys [db scroll-position counters]} [_ match]]
    (let [navigation-count (-> counters :id :navigations)
          old-match (:common/route db)
          new-match (assoc match :controllers
                           (rfc/apply-controllers (:controllers old-match) match))
          same (fn [& path]
                 (= (get-in new-match path)
                    (get-in old-match path)))]
      (if-not (and (same :data :view)
                   (same :path-params)
                   (same :query-params) ; causes some trouble with settingsbox getting stuck?
                   (same :path))
      (util/deep-merge
       {:db (-> db
               (assoc :common/route new-match)
               (assoc :common/route-last old-match)
               (update-in [:state] dissoc :error-page) ; reset 404 page in case was triggered
               ; (update-in [:state] dissoc :swap)  ; cant reset swap since in middle of running...
               (update-in [:state :exception] dissoc :page)
               (assoc-in [:state :scroll-position (-> old-match :path)] scroll-position))
        :dispatch
        [:later/dispatch {:ms 300 ; XXX like everything else this shouldn't be timed but fire after page booted (= height stabilized)
                          :dispatch [:document/set-title! new-match]}]} ; title of site
       (when (or (and (same :query-params) ; TODO maybe query params did change but also something else tho
                      (or (not (same :data :view))
                          (not (same :path-params))))
                 (not old-match)) ; restore last position if followed a link from elsewhere (even if go to top for internal links)
        {:dispatch-later
         {:ms 150
          :dispatch [:scroll/on-navigate (:path new-match) navigation-count]}}))
      
      (let [fragment (-> db :state :fragment)] ;; matches are equal (fragment not part of match)
        (if (pos? (count (seq fragment))) 
          {:db (update-in db [:state] dissoc :fragment)
           :dispatch-later {:ms 200 ; obv too much. but maybe scroll issues partly from swapper bs?
                            :dispatch [:scroll/to fragment]}}))))))

(rf/reg-fx :common/navigate-fx!
  (fn [[k & [params query]]]
    (rfe/push-state k params query)))

(rf/reg-event-fx :common/navigate!
  (fn [_ [_ url-key params query]]
    {:common/navigate-fx! [url-key params query]}))

(rf/reg-event-fx :common/set-title [debug]
  (fn [{:keys [db]} [_ title]]
    {:db (assoc-in db [:state :document :title] title)}))

(rf/reg-event-fx :later/dispatch
  (fn [{:keys [db]} [_ m]]
    {:dispatch-later m}))

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

(rf/reg-event-fx :history/set-referrer
  (fn [{:keys [db]} [_ referrer nav-type]]
    {:db (-> db
             (assoc-in [:state :browser-nav :referrer] referrer)
             (assoc-in [:state :browser-nav :nav-type] nav-type))}))

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


(rf/reg-event-fx :dispatch-in/ms     debug
  (fn [{:keys [db]} [_ ms & events]]
    {:dispatch [:dispatch-in/set {:k :in
                                  :ms ms
                                  :dispatch-n events}]}))


(rf/reg-event-fx :dispatch-in/set      [(rf/inject-cofx :gen-id [:dispatch-later])
                                        (rf/inject-cofx :now-ct)
                                        debug]
  (fn [{:keys [db id now-ct]} [_ {:keys [k ms dispatch dispatch-n]}]]
    (let [id (get-in id [:id :dispatch-later])
          events (into dispatch-n dispatch)]
      {:db (assoc-in db [:state :dispatch-in k id] {:ms ms
                                                    :elapsed 0
                                                    :events events
                                                    :started now-ct               
                                                    :js-id nil}) ;not yet running
       :dispatch-in/set [k id ms events]})))

(rf/reg-event-fx :dispatch-in/save-timeout-id
  (fn [{:keys [db]} [_ k id js-id]]
    {:db (assoc-in db [:state :dispatch-in k id :js-id] js-id)}))

(rf/reg-event-fx
 :dispatch-in/cancel      [(interceptor/path [:state :dispatch-in])]
 (fn [{:keys [db]} [_ k & [id]]]
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
 (fn [{:keys [db now-ct]} [_ k & [id]]]
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
 (fn [{:keys [db]} [_ k & [id]]]
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
     {:dispatch-n [[:http/post {:uri "/api/send-contact-email"
                                :params {:name name :email email :title title :message message}
                                :response-format (ajax/json-response-format {:keywords? true})}
                    [:contact/request-sent]]]})))

(rf/reg-event-fx :contact/request-sent [debug]
  (fn [{:keys [db]} [_ response]]
    {:db (-> db
             (assoc-in [:state :contact-form :sent?] true)
             (assoc-in [:state :contact-form :response] response))
     :dispatch-n [[:form-field [:contact-form :message] nil]
                  [:form-field [:contact-form :name] nil]
                  [:form-field [:contact-form :title] nil]
                  [:form-field [:contact-form :email] nil]]}))

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
  (fn [{:keys [db]} [_ path value blur?]]
    (merge
     {:db (assoc-in db (into [:state :form-field] path) value)}
     (when blur?
       {:dispatch [:ls/store-val (into [:form-field] path) value]})))) ; XXX slows down everything dont do it use beforeunload


(rf/reg-event-fx :reloaded
 (fn [db [_ _]]
   {:dispatch-n [[:exception nil]
                 [:diag/new :debug "JS" "Reloaded"]]}))

(rf/reg-event-fx :html/toggle-class!
 (fn [db [_ id class]]
   {:html/toggle-class [id class]}))
(rf/reg-fx :html/toggle-class
  (fn [[id class]]
    (util/toggle-class! id class)))

(rf/reg-event-fx :html/set-attr!
 (fn [db [_ id attr value]]
   {:html/set-attr [id attr value]}))
(rf/reg-fx :html/set-attr
  (fn [[id attr value]]
    (util/set-attr! id attr value)))

(rf/reg-event-fx :theme/dark-mode
 (fn [db [_ on?]]
   {:db (assoc-in db [:options :theme :dark-mode] on?)}))

(rf/reg-event-fx :theme/colorscheme
 (fn [db [_ colorscheme]]
   {:db (assoc-in db [:options :theme :colorscheme] (or colorscheme "default"))}))

; renamed store-> not fire->, should work to hide fire behind stuff so can swap out easier
(rf/reg-event-fx :store->
  (fn [{:keys [db]} [_ path data merge-fields]]
    (if (get-in db [:state :booted :firebase])
      {:firestore/set {:path path :data data
                       :set-options
                       (when merge-fields
                         {:merge true :merge-fields merge-fields})}}
      {:dispatch [:on-booted :firebase [:store-> path data merge-fields]]})))
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
  (fn [{:keys [db]} [_ path on-success on-failure]]
    (if (get-in db [:state :booted :firebase])
      (let [kind (if (even? (count path))
                   :path-document
                   :path-collection)]
        {:firestore/get (merge {kind path
                                :expose-objects true
                                :on-success [:store/on-success on-success]} ;TODO mod firestore lib to accept vectors/wrapping. goddamn
                               (when on-failure
                                 {:on-failure on-failure}))})
      {:dispatch [:on-booted :firebase [:<-store path on-success on-failure]]})))

(rf/reg-event-fx :store/on-success ; strip metadata etc
  (fn [_ [_ on-success data]]
    {:dispatch (conj on-success (util/normalize-firestore-general data))}))

(rf/reg-event-fx :store/on-success-wrapper
  (fn [_ [_ ]]))

(rf/reg-event-fx :fb/fetch-settings
  (fn [{:keys [db]} _]
    {:dispatch [:http/get-internal {:uri "/api/firebase-settings"}
                [:fb/init]
                [:fb/error]]}))

(rf/reg-event-fx :fb/error
  (fn [{:keys [db]} [_ error]]
    {:dispatch [:diag/new :error "Server error" error]}))

(rf/reg-event-fx :fb/init
  (fn [{:keys [db]} _]
    (firebase/init :firebase-app-info      (get-in db [:options :firebase :config])
                   :firestore-settings     (get-in db [:options :firebase :settings]) ; Shouldn't be used on later versions. See: https://firebase.google.com/docs/reference/js/firebase.firestore.Settings
                   :get-user-sub           [:fb/get-user]
                   :set-user-event         [:fb/set-user]
                   :default-error-handler  [:fb/error])
    {:dispatch-n [[:booted :firebase]
                  [:fb/fetch-users]]}))


(rf/reg-event-fx :<-cms
  (fn [{:keys [db]} [_ path stuff]]
    (let [{:keys [url read-api-key]} (get-in db [:strapi :auth])]
      {:dispatch
       (if url
         [:http/get {:uri (str url path)
                     :headers {:Authorization (str "bearer " read-api-key)}}
          [:content [:cms]]]
         [:on-booted :cms [:<-cms path stuff]]) })))

(rf/reg-event-fx :init/cms
  (fn [{:keys [db]} [_ data]]
    {:dispatch [:<-store [:strapi :auth] [:state [:strapi]]]}))

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


(defonce wrapped-event-counter (atom 0))

(defn gen-wrapped-event-fx "Dirty hack because I'm too lazy to mod a lib where on-success won't take arguments. And I wanted to try this"
  [name-space prefix wrapped-event & args]
  (let [wrapper-key (keyword (str (name name-space) "/"
                                  (name prefix) "-"
                                  (name wrapped-event) "-"
                                  (swap! wrapped-event-counter inc))) ; so no clash if same event wrapped many times
        f (fn [{:keys [db]} [_ result]]
            {:dispatch [(-> [wrapped-event] ;or just flatten lol
                            (into args)
                            (into result))]})]
    (rf/reg-event-fx wrapper-key f)
    wrapper-key))

; stuff could do
; dynamically generate event handlers which close over args, preserving them
; ^ I like this just for how insane it is
; stash and retrieve args by interceptor
; ^ more ideomatic reframe and way less messy but requires, fuck if I know?
; handler event would still need to know where to look for args or well
; no could just be one spot, so one interceptor for stashing and one for retrieving

; but dynamic event reg seems like decent enough thing anyways
; could also do stuff like chaining multiple events into one...
; just grab the registered fns and feed them into eachother.
;
; and because events can trigger event creation some stuff otherwise done in
; a block within an event (so we see in, and out) could be spread over multiple dynamic events?
; 
; _________________________________
; IDEA FOR UNIFORM TRANSITIONS OUT
; macro or fn (sometimes) replacing reg-event-fx and reg-sub
; creating a second one with a -soft postfix or similar.
; Actually guess event side easiest implemented as interceptor...
; whatever is written to app-db, we copy it to our "shadow app-db"
; BUT going to false or nil means deferring this for t ms.
; 
; Meaning all we do on "down" side is dispatch-later...
;
; sub to soft app-db remains, but somehow set a flag right
; that closing/disappearing/ending has kicked off and we better clean up asap
;
; either above, or writing to somewhere specific in app-db gets shadowed
; so don't have to think about changing event defs.
; [:state-fuzzy] all paths mirror state exceot instead of `item`, [item state]
; copier interceptor write latter same time change made to :state
; then a dispatch-later for changing first
; with a simple destructuring anything with :state / :content etc input
; would work same as before (but no extra features), then optionally make use of second.

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

(rf/reg-event-fx :booted [debug]
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
   (let [value (get-in ls ls-path)]
     {:db (update-in db db-path (or (and (seqable? value) merge)
                                    assoc)
                     value)})))

(rf/reg-event-fx :ls/get-path-as-event   [(rf/inject-cofx :ls)]
 (fn [{:keys [_ ls]} [_ ls-path event]]
   (let [value (get-in ls ls-path)]
     {:dispatch (conj event value)})))

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
                 (if accepted? ; TODO else should also refrain from cookies obviously hah
                   [:ls/store-val [:cookie-notice-accepted] true]
                   [:state [:cookies-allowed] false])]})) ; and then try to make google and shit actually not. how haha?

(rf/reg-event-fx :hide-header-footer  [(rf/inject-cofx :css-var [:header-with-menu-height])
                                       (rf/inject-cofx :css-var [:header-height])
                                       (rf/inject-cofx :css-var [:footer-height])]
 (fn [{:keys [db css-var]} [_ hide-header? hide-footer?]]
   (let [header-height (if hide-header?
                         "var(--space)"
                         (if (get-in db [:state :menu])
                            (:header-with-menu-height css-var)
                            (:header-height css-var)))
         at-bottom?    (get-in db [:state :scroll :at-bottom])
         footer-height (if hide-footer?
                         (if at-bottom?
                           "0px"
                           "calc(var(--space) + 2 * var(--line-width))")
                         (:footer-height css-var))]
     {:db (-> db (assoc-in [:state :hidden :header] hide-header?)
                 (assoc-in [:state :hidden :footer] hide-footer?))
      :dispatch-n [[:->css-var! "header-height-current" header-height]
                   (when-not (get-in db [:state :scroll :at-bottom]) ; wait what
                     [:->css-var! "footer-height-current" footer-height])]})))

(rf/reg-event-fx :init/init  [] ;; Init stuff in order and depending on how page reloads (that's still very dev-related tho...)
 (fn [{:keys [db]} [_ _]]
  {:dispatch-n [[:listener/load]
                [:ls/get-path [:scroll-position] [:state :scroll-position]]
                [:listener/scroll]
                [:scroll/update-direction]
                [:scroll/update-css-var]
                [:listener/popstate-back]
                ; [:listener/global-click]
                [:listener/visibility-change]
                [:listener/before-unload-save-scroll]
                ; [:on-booted :firebase [:id-counters/fetch]]
                [:ls/get-path [:form-field] [:state :form-field]] ; restore any active form-fields
                [:ls/get-path [:cv-visited] [:state :cv :visited]] ; should rather spec which paths to load and then do that (in one op)
                [:cookie/show-notice]
                [:on-booted :firebase [:init/cms]]
                [:on-booted :firebase [:init/imagor]]
                [::bp/set-breakpoints
                 :breakpoints [:mobile 560
                               :tablet 992
                               :small-monitor 1200
                               :large-monitor]
                 :debounce-ms 250]
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
    (let [id (get opts :loading-id (random-uuid))
          loading-key (get opts :loading kind)
          cleanup [:loading/off loading-key id]] ; set something to indicate request is underway
      {:dispatch [:loading/on loading-key id]   ;; tho want this per-request so figure out. by passing path frag maybe... slightly better now at least
       :http-xhrio
       (merge
        {:method          kind
         :timeout         8000                                           ;; optional see API docs
         :response-format (ajax/transit-response-format)  ;; IMPORTANT!: You must provide this.
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
               {:format (ajax/json-request-format)
                :response-format (ajax/json-response-format {:keywords? true})}))

(rf/reg-event-fx :http/put [debug]
  (get-http-fn :put
               {:multipart-params :something})) ;file upload..


(rf/reg-event-fx :default-http-result
 (fn [db [_ res]]
   {:dispatch [:diag/new :debug "HTTP" (str res)]}))
(rf/reg-event-fx :default-http-error
 (fn [db [_ {:as res :keys [uri status status-text failure]}]]
   {:dispatch [:diag/new :error "HTTP" (str status
                                            (some-> status-text (str " "))
                                            ": " uri)]}))

(rf/reg-event-fx :http-result-wrapper
 (fn [db [_ handler cleanup res]]
   {:dispatch-n [(into handler [res])
                 cleanup]}))

(rf/reg-event-fx
 :handle-visibility-change
 (fn [{db :db} [_ hidden-prop-name]]
   (let [visible? (not (gobj/get js/document hidden-prop-name))]
     (prn "chrome tab visibility changed:" visible?)
     {:db (assoc db [:state :tab-visible] visible?)})))

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

(rf/reg-event-fx :init/imagor
 (fn [{:keys [db]} [_ _]]
  (when-not (get db :imagor)
    {:dispatch [:<-store [:imagor :auth] [:state [:imagor]]]})))

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


(rf/reg-event-fx :log/write
  (fn [{:keys [db]} [_ level title message]]
    {:log/write! [level title message]}))
(rf/reg-fx :log/write!
  (fn [[level title message]]
    (util/log level title message)))


(rf/reg-event-fx :darken/but-element
 (fn [{:keys [db]} [_ id-or-class timeout]]
   {:db (assoc-in db [:state :darken-but] id-or-class)
    :dispatch-n [[:html/toggle-class! id-or-class "darken-fadeout-restore"]
                 [:html/toggle-class! nil "darken-fadeout"]]
    :dispatch-later {:ms timeout
                     :dispatch [:darken/restore id-or-class]}}))

(rf/reg-event-fx :darken/restore
 (fn [{:keys [db]} [_ id-or-class]]
   {:db (update-in db [:state] dissoc :darken-but)
    :dispatch-n [[:html/toggle-class! id-or-class "darken-fadeout-restore"]
                 [:html/toggle-class! nil "darken-fadeout"]]}))


(rf/reg-event-fx :global-clicked [debug]
 (fn [{:keys [db]} [_ e]]
   {:db (assoc-in db [:state :global-clicked] e)
    :dispatch-later {:ms 300
                     :dispatch [:state [:global-clicked] nil]}}))

(rf/reg-event-fx :poll/start
 (fn [{:keys [db]} [_ ]]
   {:dispatch [::poll/set-rules
  [;; rule #1
   {:interval                 60
    :event                    [:update-ts 60]
    :poll-when                [:chat/visible?]}

   ;; rule #2
   {:interval                 6
    :event                    [:events/log "POLL (every 6 seconds)"]
    :poll-when                [:subs/poll?]
    :dispatch-event-on-start? false} ]]}))
