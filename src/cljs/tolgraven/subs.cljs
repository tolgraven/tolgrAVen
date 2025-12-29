(ns tolgraven.subs
  (:require [re-frame.core :as rf]
            [tolgraven.db :as db]
            [tolgraven.util :as util]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [reitit.frontend.easy :as rfe]
            [cljs-time.coerce :as ctc]
            [cljs-time.core :as ct]))

(rf/reg-sub :get ;should this be discontinued? or only used transiently like migrate everything away once got a comp working?
 (fn [db [_ & path]]
  (get-in db (if (seqable? path) path [path])))) ;either way prob skip the destructuring and shit, runs too often...

(rf/reg-sub :nil (fn [_ _])) ; hah why ; from :text-color using it: "eh, worth? assuming this is a wrong-sub with no db input and we do have a lot of subs for this so"

(rf/reg-sub :content ; TODO break up to smaller chunks...
 (fn [db [_ path]]
   (get-in db (into [:content] path))))

(rf/reg-sub :state
  (fn [db [_ path]] ;change to path?
    (get-in db (into [:state] path))))

(rf/reg-sub :option
  (fn [db [_ path]]
    (get-in db (into [:options] path))))

(rf/reg-sub :debug
  (fn [db [_ path]]
    (get-in db (into [:state :debug] path))))

(rf/reg-sub :exception
  (fn [db [_ path]]
    (get-in db (into [:state :exception] path))))

(rf/reg-sub :form-field
  (fn [db [_ path]]
    (get-in db (into [:state :form-field] path))))

(rf/reg-sub :<-store
  :<- [:booted? :firebase]
  (fn [initialized [_ & coll-docs]]
    (when initialized
      (let [look-in (if (even? (count coll-docs))
                      {:path-document coll-docs}
                      {:path-collection coll-docs})]
        (some-> (rf/subscribe [:firestore/on-snapshot look-in])
                deref
                :data
                (walk/keywordize-keys))))))

(rf/reg-sub :<-store-2 ; newer version which mostly works but not quite everywhere, differing in how keys are handled...
  :<- [:booted? :firebase]
  (fn [initialized [_ & coll-docs]]
    (when initialized
      (let [look-in (if (even? (count coll-docs))
                      {:path-document (vec coll-docs)}
                      {:path-collection (vec coll-docs)})]
        (some-> (rf/subscribe [:firestore/on-snapshot look-in])
                deref
                util/normalize-firestore-general)))))

(rf/reg-sub :<-store-q
  :<- [:booted? :firebase]
  (fn [initialized [_ opts]]
    (when initialized
      (some-> (rf/subscribe [:firestore/on-snapshot opts])
                deref
                util/normalize-firestore-general))))

; DONE PROPERLY. but somehow ends up with sub sometimes never returning anything but {} (as always does first run)
; WHAT THE FUCK honestly. 
; (rf/reg-sub :<-store-q
;   (fn [[_ opts]]
;     ; (util/log (str "firebase init: " (.-length js/firebase.app)))
;     [(rf/subscribe [:booted? :firebase])
;      (rf/subscribe (if (pos? (.-length js/firebase.app))
;                      [:firestore/on-snapshot opts]
;                      [:nil]))])
;   (fn [[initialized snapshot] [_ opts]]
;     (when initialized
;       (some-> snapshot
;               util/normalize-firestore-general))))
               

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

(rf/reg-sub :get-css-var ; is obviously problematic (not pure, doesnt actually auto update(?)) so figure out way around
 (fn [db [_ var-name]]
   (util/<-css-var var-name)))

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
   (let [params (:path-params route)
         query (merge (:query-params route) query-map)]
    (rfe/href k params query))))


(rf/reg-sub :href
 :<- [:common/page-id]           
 :<- [:common/route]           
 (fn [[page-id route] [_ k & [params query]]] ;"Like rfe/href, but preserves existing query"
  (when (and page-id k)
    (let [path (if (keyword? k)
                 k
                 page-id)
          query (merge (:query-params route) query)
          uri (rfe/href path params query)]
      (if (keyword? k)
        uri
        (string/replace (or uri "")
                        #"/(\w.*)?(\?.*)?"
                        (str "/" "$1" "$2" k)))))))

(defn imagor-hasher
  [imagor-key url]
  nil) ;implement

(rf/reg-sub :href-external-img ; "Like href, but for external images" <- gen by copilot lol
 :<- [:imagor :auth]    ; uses imagor to fetch and optionally mod ext images (avatars, instagram etc)
 (fn [imagor [_ url & transforms]]
  (let [host (:host imagor)
        prefix (or (imagor-hasher (:key imagor) url) "unsafe")]
    (str host "/" prefix "/"
         (some-> (string/join "/" transforms)
                 (str "/"))
         url))))

(rf/reg-sub :imagor
 (fn [db [_ k]]
  (get-in db [:state :imagor k])))


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
