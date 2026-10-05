(ns tolgraven.subs
  (:require [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            [re-frame.db :as rfdb]
            [reagent.ratom :as ratom]
            [tolgraven.content.client :as content]
            [tolgraven.content.contract :as content-contract]
            [tolgraven.util :as util]
            [tolgraven.supabase.client :as supabase-client]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.plan :as plan]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [reitit.frontend.easy :as rfe]))

(rf/reg-sub-raw :store/on-snapshot
  (fn [db [_ opts]]
    (if context/*server?*
      (ratom/make-reaction
        #(or (when (:scoped? opts)
               (get-in @db [:store :scoped (pr-str (query/normalize-query opts))]))
             (query/query-contract (get-in @db [:store :public]) opts)))
      (supabase-client/ensure-query! opts))))

(rf/reg-sub-raw :store/plan
  (fn [_ [_ nodes context]]
    (ratom/make-reaction
     #(plan/evaluate nodes context
                     (fn [queries] (mapv (fn [opts] @(rf/subscribe [:store/on-snapshot opts])) queries))))))

(rf/reg-sub :get ;should this be discontinued? or only used transiently like migrate everything away once got a comp working?
 (fn [db [_ & path]]
  (get-in db (if (seqable? path) path [path])))) ;either way prob skip the destructuring and shit, runs too often...

(rf/reg-sub :nil (fn [_ _])) ; hah why ; from :text-color using it: "eh, worth? assuming this is a wrong-sub with no db input and we do have a lot of subs for this so"

(rf/reg-sub-raw :content
  (fn [db [_ path]]
    ;; Re-frame owns/disposes this reaction with its last consumer. Only the
    ;; request is a side effect; the data remains ordinary cached app-db content.
    (let [ks (if (seq path)
               (filterv (set content-contract/sections) [(first path)])
               content-contract/sections)]
      ;; Runtime API data (GitHub, Instagram, etc.) shares :content but is not
      ;; CMS content. Its own module owns loading and error reporting.
      (when (and (seq ks) (not context/*server?*)) (rf/dispatch [:content/load ks]))
      (ratom/make-reaction #(get-in @db (into [:content] path))))))

(rf/reg-sub :state
  (fn [db [_ path]] ;change to path?
    (get-in db (into [:state] path))))

(rf/reg-sub :option
  (fn [db [_ path]]
    (get-in db (into [:options] path))))

(rf/reg-sub :debug
  :<- [:state]
  (fn [state [_ path]]
    (get-in state (into [:debug] path))))

(rf/reg-sub :exception
  :<- [:state]
  (fn [exception [_ path]]
    (get-in exception (into [:exception] path))))

(rf/reg-sub :form-field
  :<- [:state]
  (fn [state [_ path]]
    (get-in state (into [:form-field] path))))

(defn- store-inputs [[_ & coll-docs]]
  [(rf/subscribe [:store/on-snapshot
                  {(if (even? (count coll-docs)) :path-document :path-collection)
                   (vec coll-docs)}])
   (rf/subscribe [:booted? :store])])

(rf/reg-sub :<-store
  store-inputs
  (fn [[response initialized] _]
    (when (or initialized (some? response))
      (some-> response :data walk/keywordize-keys))))

(rf/reg-sub :<-store-2
  store-inputs
  (fn [[response initialized] _]
    (when (or initialized (some? response))
      (some-> response util/normalize-store-result))))

(rf/reg-sub :<-store-q
  (fn [[_ opts]]
    [(rf/subscribe [:store/on-snapshot opts])
     (rf/subscribe [:booted? :store])])
  (fn [[res initialized] [_ _]]
    (when (or initialized (some? res))
      (some-> res
              util/normalize-store-result))))

(rf/reg-sub :header-text
 :<- [:state [:is-personal]]
 :<- [:content [:header]]
 (fn [[is-personal header] [_ _]]
   (if is-personal
     (:text-personal header)
     (:text header))))


(rf/reg-sub :common/route
  (fn [db [_ last?]]
    (get-in db (if last? [:common/route-last] [:common/route]))))

(rf/reg-sub :common/page-id
  (fn [[_ last?]] (rf/subscribe [:common/route last?]))
  (fn [route _] (-> route :data :name)))

(rf/reg-sub :common/page
  (fn [[_ last?]]  (rf/subscribe [:common/route last?]))
  (fn [route [_ _]] (-> route :data :view)))

(rf/reg-sub :carousel/index
  :<- [:state [:carousel]]
  (fn [carousel [_ id]]
    (prn carousel id)
    (get-in carousel [id :index] 0)))

(rf/reg-sub :get-css-var
  (fn [db [_ var-name]]
    (get-in db [:state :css-var var-name])))

(rf/reg-sub :menu
 (fn [db [_ _]]
   (get-in db [:state :menu])))

(rf/reg-sub :loading
 :<- [:state [:is-loading]]
 (fn [loading [_ kind id]]
   (let [category (get loading kind)
         specific (some #{id} category)]
     (if (some? category)
       (if id
         (boolean specific)
         (boolean (seq category)))
       (not-every? nil? (map seq (vals loading))))))) ;if no args passed just check if any active load

(rf/reg-sub :scope/inited?
 :<- [:state [:init]]
 (fn [init [_ scope-]]
   (let [scope-' (get-in init [:scope scope-])]
     (boolean scope-'))))

(rf/reg-sub :diag/messages
 (fn [db [_ _]]
   (get-in db [:diagnostics :messages])))

(rf/reg-sub :diag/message
 :<- [:diag/messages]
 (fn [messages [_ id]]
   (get messages id)))

(rf/reg-sub :diag/unhandled
 :<- [:get :diagnostics :unhandled]
 :<- [:diag/messages]
 (fn [[unhandled-ids messages] [_ _]]
   (map messages unhandled-ids)))


(rf/reg-sub :hud ;so this should massage :diagnostics and only return relevant stuff
 :<- [:get :diagnostics]
 :<- [:option [:hud]]
 :<- [:get :hud]
 (fn [[{:keys [messages unhandled]} ;unhandled just contains ids
       {:keys [timeout level]} ;minimum level
       hud]
      [_ & [request-key]]] ;could be like :modal, :error...
  (case request-key
   :modal (when (:modal hud) (get messages (:modal hud))) ;fetch message by id...
   (let [including (conj (take-while #(not= % level)
                                     [:error :warning :info])
                         level)]
     (filter #(some #{(:level %)} including)
             (map messages unhandled))))))

(rf/reg-sub :modal
 (fn [db [_ _]]
   (get-in db [:state :modal-zoom])))

(rf/reg-sub :history/nav
 :<- [:state [:browser-nav]]
 (fn [nav]
   nav))

(rf/reg-sub :history/popped?
 :<- [:state [:browser-nav]]
 :<- [:common/route]
 :<- [:common/route :last]
 (fn [[nav route last]]
   (and (:got-nav nav)
        (not= (:path route)
              (:path last)))))

(rf/reg-sub :history/back-nav-from-external?
 :<- [:history/nav]
 :<- [:common/route :last]
 (fn [[nav last] [_]]
   (and (not (get-in last [:data :name]))
        (or (and (seq (:referrer nav))
                 (not (string/includes? (:referrer nav) "tolgraven")))
            (and (= "" (:referrer nav))
                 (= (:nav-type nav) 2))))))


(rf/reg-sub :href-add-query ;  "Append query to href of current page, or passed k/params"
 :<- [:common/page-id]           
 :<- [:common/route]
 (fn [[k route] [_ query-map]]
   (when k
     (let [params (:path-params route)
           query (merge (:query-params route) query-map)]
       (context/href rfe/href k params query)))))


(rf/reg-sub :href
 :<- [:common/page-id]           
 :<- [:common/route]           
 (fn [[page-id route] [_ k & [params query]]] ;"Like rfe/href, but preserves existing query"
  (when (and page-id k)
    (let [path (if (keyword? k)
                 k
                 page-id)
          query (merge (:query-params route) query)
          params (if (keyword? k) params (merge (:path-params route) params))
          uri (context/href rfe/href path params query)]
      (if (keyword? k)
        uri
        (string/replace (or uri "")
                        #"/(\w.*)?(\?.*)?"
                        (str "/" "$1" "$2" k)))))))

(rf/reg-sub :href-external-img
  (fn [_ [_ url & transforms]]
    (str "/api/integrations/image?url=" (js/encodeURIComponent url)
         "&transforms=" (js/encodeURIComponent (string/join "/" transforms)))))

(rf/reg-sub :fullscreen/get
 :<- [:state [:fullscreen]]           
 (fn [fullscreen [_ k]]
  (if k
    (get fullscreen k)
    fullscreen)))

(rf/reg-sub :fullscreen/any?
 :<- [:state [:fullscreen]]           
 (fn [fullscreen [_ _]]
  (apply some? (filter true? (vals fullscreen)))))

(rf/reg-sub :booted?
 :<- [:state [:booted]]           
 (fn [booted [_ k]]
  (get booted k false)))

; (rf/reg-sub :theme/dark-mode
;  :<- [:option [:theme]]           
;  (fn [theme [_ _]]
;   (get theme :dark-mode true)))

; (rf/reg-sub :theme/colorscheme
;  :<- [:option [:theme]]           
;  (fn [theme [_ _]]
;   (get theme :colorscheme "default")))

(rf/reg-sub :common/page-ready?
  :<- [:common/page]
  (fn [page _] (some? page)))

(rf/reg-sub :timestamp
  :<- [:state [:ssr]]
  (fn [ssr [_ ts]]
    (or (when (:hydrating? ssr) (get-in ssr [:dates ts])) (util/timestamp ts))))
