(ns website.subs
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  #?(:cljs (:require [website.react :as r]
            [website.render-context :as context]


            [website.content.client :as content]
            [website.content.fields :as fields]
            [website.content.contract :as content-contract]
            [website.util :as util]
            #?(:module/supabase [website.supabase.runtime :as supabase-client])
            [website.supabase.query :as query]
            [website.supabase.plan :as plan]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [reitit.frontend.easy :as rfe])))

#?(:cljs
(do

(when-enabled :supabase nil
(r/reg-sub-raw :store/on-snapshot
  (fn [db [_ opts]]
    (if context/*server?*
      (r/make-reaction
        #(or (when (:scoped? opts)
               (get-in @db [:store :scoped (pr-str (query/normalize-query opts))]))
             (query/query-contract (get-in @db [:store :public]) opts)))
      (supabase-client/ensure-query! opts))))

(r/reg-sub-raw :store/plan
  (fn [_ [_ nodes context]]
    (r/make-reaction
     #(plan/evaluate nodes context
                     (fn [queries] (mapv (fn [opts] @(r/subscribe [:store/on-snapshot opts])) queries))))))

)

(r/reg-sub :get ;should this be discontinued? or only used transiently like migrate everything away once got a comp working?
 (fn [db query]
   (let [[_ & path] (or (:re-frame/query-v query) query)]
     (get-in db path)))) ;either way prob skip the destructuring and shit, runs too often...

(r/reg-sub :nil (fn [_ _])) ; hah why ; from :text-color using it: "eh, worth? assuming this is a wrong-sub with no db input and we do have a lot of subs for this so"

(r/reg-sub-raw :content
  (fn [db [_ path]]
    ;; Re-frame owns/disposes this reaction with its last consumer. Only the
    ;; request is a side effect; the data remains ordinary cached app-db content.
    (let [ks (if (seq path)
               (filterv (set content-contract/sections) [(first path)])
               content-contract/sections)]
      ;; Runtime API data (GitHub, Instagram, etc.) shares :content but is not
      ;; CMS content. Its own module owns loading and error reporting.
      (when (and (seq ks) (not context/*server?*)) (r/dispatch [:content/load ks]))
      (r/make-reaction #(get-in @db (into [:content] path))))))

(r/reg-sub :content/mode
  (fn [db _]
    (let [user (get-in db [:state :active-user])
          editor (get-in db [:state :content-editor])]
      (if (and (:enabled? editor) (= (:id user) (:owner editor))
               (some #{"admins"} (:roles user))) :edit :view))))

(r/reg-sub :content/view
  (fn [[_ path]] [(r/subscribe [:content path]) (r/subscribe [:content/mode])])
  (fn [[value mode] [_ path]] (fields/annotate value path mode)))

(r/reg-sub :state
  (fn [db [_ path]] ;change to path?
    (get-in db (into [:state] path))))

(r/reg-sub :option
  (fn [db [_ path]]
    (get-in db (into [:options] path))))

(r/reg-sub :debug
  :<- [:state]
  (fn [state [_ path]]
    (get-in state (into [:debug] path))))

(r/reg-sub :exception
  :<- [:state]
  (fn [exception [_ path]]
    (get-in exception (into [:exception] path))))

(r/reg-sub :form-field
  :<- [:state]
  (fn [state [_ path]]
    (get-in state (into [:form-field] path))))

(defn- store-inputs [[_ & coll-docs]]
  [(r/subscribe [:store/on-snapshot
                  {(if (even? (count coll-docs)) :path-document :path-collection)
                   (vec coll-docs)}])
   (r/subscribe [:booted? :store])])

(r/reg-sub :<-store
  store-inputs
  (fn [[response initialized] _]
    (when (or initialized (some? response))
      (some-> response :data walk/keywordize-keys))))

(r/reg-sub :<-store-2
  store-inputs
  (fn [[response initialized] _]
    (when (or initialized (some? response))
      (some-> response util/normalize-store-result))))

(r/reg-sub :<-store-q
  (fn [[_ opts]]
    [(r/subscribe [:store/on-snapshot opts])
     (r/subscribe [:booted? :store])])
  (fn [[res initialized] [_ _]]
    (when (or initialized (some? res))
      (some-> res
              util/normalize-store-result))))

(r/reg-sub :header-text
 :<- [:state [:is-personal]]
 :<- [:content/view [:header]]
 (fn [[is-personal header] [_ _]]
   (if is-personal
     (:text-personal header)
     (:text header))))


(r/reg-sub :common/route
  (fn [db [_ last?]]
    (get-in db (if last? [:common/route-last] [:common/route]))))

(r/reg-sub :common/page-id
  (fn [[_ last?]] (r/subscribe [:common/route last?]))
  (fn [route _] (-> route :data :name)))

(r/reg-sub :common/page
  (fn [[_ last?]]  (r/subscribe [:common/route last?]))
  (fn [route [_ _]] (-> route :data :view)))

(r/reg-sub :get-css-var
  (fn [db [_ var-name]]
    (get-in db [:state :css-var var-name])))

(r/reg-sub :menu
 (fn [db [_ _]]
   (get-in db [:state :menu])))

(r/reg-sub :loading
 :<- [:state [:is-loading]]
 (fn [loading [_ kind id]]
   (let [category (get loading kind)
         specific (some #{id} category)]
     (if (some? category)
       (if id
         (boolean specific)
         (boolean (seq category)))
       (not-every? nil? (map seq (vals loading))))))) ;if no args passed just check if any active load

(r/reg-sub :scope/inited?
 :<- [:state [:init]]
 (fn [init [_ scope-]]
   (let [scope-' (get-in init [:scope scope-])]
     (boolean scope-'))))

(r/reg-sub :diag/messages
 (fn [db [_ _]]
   (get-in db [:diagnostics :messages])))

(r/reg-sub :diag/message
 :<- [:diag/messages]
 (fn [messages [_ id]]
   (get messages id)))

(r/reg-sub :diag/unhandled
 :<- [:get :diagnostics :unhandled]
 :<- [:diag/messages]
 (fn [[unhandled-ids messages] [_ _]]
   (map messages unhandled-ids)))


(r/reg-sub :hud ;so this should massage :diagnostics and only return relevant stuff
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

(r/reg-sub :modal
 (fn [db [_ _]]
   (get-in db [:state :modal-zoom])))

(r/reg-sub :history/nav
 :<- [:state [:browser-nav]]
 (fn [nav]
   nav))

(r/reg-sub :history/popped?
 :<- [:state [:browser-nav]]
 :<- [:common/route]
 :<- [:common/route :last]
 (fn [[nav route last]]
   (and (:got-nav nav)
        (not= (:path route)
              (:path last)))))

(r/reg-sub :history/back-nav-from-external?
 :<- [:history/nav]
 :<- [:common/route :last]
 (fn [[nav last] [_]]
   (and (not (get-in last [:data :name]))
        (or (and (seq (:referrer nav))
                 (not (string/includes? (:referrer nav) "tolgraven")))
            (and (= "" (:referrer nav))
                 (= (:nav-type nav) 2))))))


(r/reg-sub :href-add-query ;  "Append query to href of current page, or passed k/params"
 :<- [:common/page-id]           
 :<- [:common/route]
 (fn [[k route] [_ query-map]]
   (when k
     (let [params (:path-params route)
           query (merge (:query-params route) query-map)]
       (context/href rfe/href k params query)))))


(r/reg-sub :href
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

(r/reg-sub :href-external-img
  (fn [_ [_ url & transforms]]
    (str "/api/integrations/image?url=" (js/encodeURIComponent url)
         "&transforms=" (js/encodeURIComponent (string/join "/" transforms)))))

(r/reg-sub :fullscreen/get
 :<- [:state [:fullscreen]]           
 (fn [fullscreen [_ k]]
  (if k
    (get fullscreen k)
    fullscreen)))

(r/reg-sub :fullscreen/any?
 :<- [:state [:fullscreen]]           
 (fn [fullscreen [_ _]]
  (apply some? (filter true? (vals fullscreen)))))

(r/reg-sub :booted?
 :<- [:state [:booted]]           
 (fn [booted [_ k]]
  (get booted k false)))

(r/reg-sub :common/page-ready?
  :<- [:common/page]
  (fn [page _] (some? page)))

(r/reg-sub :timestamp
  :<- [:state [:ssr]]
  (fn [ssr [_ ts]]
    (or (when (:hydrating? ssr) (get-in ssr [:dates ts])) (util/timestamp ts))))

))
