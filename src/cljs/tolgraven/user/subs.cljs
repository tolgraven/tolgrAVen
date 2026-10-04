(ns tolgraven.user.subs
  (:require
   [tolgraven.react :as rf]
   [tolgraven.supabase.query :as query]
   [clojure.walk :as walk]))

(rf/reg-sub :user/user
  (fn [[_ user]]
    (rf/subscribe (if (and user (not (coll? user))) [:<-store-q (query/profile-query user)] [:nil])))
  (fn [records [_ user]] (if (map? user) user (first (vals records)))))

(rf/reg-sub :user/has-role? :<- [:user/active-user]
  (fn [user [_ role]] (boolean (some #{(name role)} (:roles user)))))

(rf/reg-sub :user/trusted? :<- [:option [:supabase :trusted-author-ids]]
  (fn [ids [_ id]] (boolean (some #{id} ids))))

(rf/reg-sub :user/default-avatar
 :<- [:get :content :common :user-avatar-fallback]
 (fn [fallback [_ user-level]]
   fallback))


(rf/reg-sub :user/active-user
 :<- [:state [:active-user]]
 (fn [user [_ _]]
   (when user
     ;; Public counters/profile fields stay live while private roles and vote
     ;; history remain the result of the verified owner endpoint.
     (merge user @(rf/subscribe [:user/user (:id user)])))))

(rf/reg-sub :user/active-section
 :<- [:state [:user-section]]
 (fn [section [_ _]]
   section))

(rf/reg-sub :user/ui-open?
 :<- [:user/active-section]
 (fn [section [_ _]]
   (and (some? section)
        (not (some #{:closed} section)))))


; (rf/reg-sub :user/status
;  (fn [db [_ user-id]]
;    (-> @(rf/subscribe [:user/user used-id]) :status)))

(rf/reg-sub :login/field
 :<- [:state [:login-field]]
 :<- [:state [:register-field]]
 (fn [[login register] [_ field]]
  (or (field login)
      (field register))))

(rf/reg-sub :login/valid-input?
 :<- [:form-field [:login]]
 (fn [login-field [_ field]]
   (and (pos? (count (:email   login-field)))
        (<= 6 (count (:password login-field)))))) ; do proper validation tho talk to server and greenlight when correc.

(rf/reg-sub :login/valid-email?
 :<- [:form-field [:login]]
 :<- [:login/valid-input?]
 (fn [[login-field valid-input] [_ field]]
  (->> (or (:email login-field) "")
       (re-find #"\w+@\w+\.\w+")
       boolean)))

(rf/reg-sub :user/error ; login-error, rename...
 :<- [:diag/unhandled]
  (fn [unhandled [_ _]]
    (->> unhandled
         (filter #(= (:title %) "Sign in"))
         last
         :message)))
