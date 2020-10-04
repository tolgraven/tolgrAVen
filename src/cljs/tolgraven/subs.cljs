(ns tolgraven.subs
  (:require [re-frame.core :as rf]
            [tolgraven.db :as db]
            [tolgraven.util :as util]
            [clojure.edn :as edn]
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


; (rf/subscribe [:state])
(rf/reg-sub :users
 :<- [:get [:users]]
 (fn [users [_ path]]
   (get-in users path)))

(rf/reg-sub :user/session
 :<- [:state [:user]]
 (fn [user [_ _]]
   (-> user :session)))

(rf/reg-sub :user/status
 :<- [:user/session]
 (fn [session [_ _]]
   (-> session :status)))


(rf/reg-sub :blog/content ;terrible name, basically everything not posts. posts should have own category altogether anyways...
 :<- [:content [:blog]]
 (fn [content [_ path]]
   (get-in content path)))

(rf/reg-sub :blog/posts
 :<- [:content [:blog]]
 (fn [blog [_ id]]
   (cond-> (:posts blog) id id)) )

(rf/reg-sub :blog-comment-input
 :<- [:state]
 (fn [state [_ id]]
   (or (->> state :blog (nth id) :comments :input) "")))


(rf/reg-sub :header-text
 :<- [:state]
 :<- [:content :header]
 (fn [[state header] [_ _]]
   (if (:is-personal state)
     (:text-personal header)
     (:text header))))

(rf/reg-sub :common/route
  (fn [db _] (-> db :common/route)))
(rf/reg-sub :common/page-id :<- [:common/route]
  (fn [route _] (-> route :data :name)))
(rf/reg-sub :common/page    :<- [:common/route]
  (fn [route _] (-> route :data :view)))


(rf/reg-sub :get-css-var
 (fn [_ [_ var-name]]
   (util/<-css-var var-name)))

(rf/reg-sub :menu
 (fn [db [_ item]]
   (cond-> (get-in db [:state :menu])
     item item)))

(rf/reg-sub :hud ;so this should massage :diagnostics and only return relevant stuff
 :<- [:get :diagnostics]
 :<- [:get :options :hud]
 :<- [:get :hud]
 (fn [[{:keys [messages unhandled]}
       {:keys [timeout level]}
       hud]
      [_ & [request-key]]] ;could be like :modal, :error...
  (case request-key
   :modal (when (:modal hud) (get messages (:modal hud))) ;fetch message by id...
   (let [including (conj (take-while #(not= % level)
                                    [:error :warning :info]) level)]
   #_(filter #(ct/after? (ct/plus (:time %) (ct/seconds timeout)) (ct/now)) diag)
   (filter #(some #{(:level %)} including)
           (map messages unhandled))))))
