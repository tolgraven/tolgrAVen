(ns tolgraven.subs
  (:require [re-frame.core :as rf]
            [tolgraven.db :as db]
            [tolgraven.util :as util]
            [tolgraven.blog.subs]
            [tolgraven.user.subs]
            [tolgraven.strava.subs]
            [tolgraven.instagram.subs]
            [tolgraven.chat.subs]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.string :as string]
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
  :<- [:state [:debug]]
  (fn [debug [_ option]]
    (get-in debug option)))

(rf/reg-sub :exception
  (fn [db [_ path]]
    (get-in db (into [:state :exception] path))))

(rf/reg-sub :form-field
  (fn [db [_ path]]
    (get-in db (into [:state :form-field] path))))

(rf/reg-sub :<-store
  :<- [:state [:firebase-initialized]]
  (fn [initialized [_ & coll-docs]]
    (when initialized
      (let [look-in (if (even? (count coll-docs))
                      {:path-document coll-docs}
                      {:path-collection coll-docs})]
        (-> @(rf/subscribe [:firestore/on-snapshot look-in])
            :data
            (walk/keywordize-keys))))))


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

(rf/reg-sub :now ;not how this is supposed to work eheh
 (fn [_ _])
  js/Date.now)

(rf/reg-sub :now-ct
:<- [:now]
 (fn [now _]
  (ctc/from-long now)))

(rf/reg-sub :get-css-var
 (fn [_ [_ var-name]]
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
   (get-in db [:state :modal])))

(rf/reg-sub :github/commits
 :<- [:content [:github]]
 (fn [github]
   (:repo github)))

(rf/reg-sub :github/commit-count
 :<- [:content [:github :repo-headers]]
 (fn [headers]
   (if headers
     (-> headers
         :link
         (string/split "page=") last
         (string/split ">")     first
         js/parseInt)
     "Loading")))

(rf/reg-sub :history/popped?
 :<- [:state [:browser-nav]]
 (fn [nav]
   (:got-nav nav)))
