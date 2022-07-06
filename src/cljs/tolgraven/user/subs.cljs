(ns tolgraven.user.subs
  (:require
   [re-frame.core :as rf]
   [clojure.walk :as walk]))

(rf/reg-sub :fb/get-user
 :<- [:user/active-user]
 (fn [user _]
   user))

(rf/reg-sub :user/users
 :<- [:<-store :users]
 (fn [users [_ path]]
   users))

(rf/reg-sub :user/user ;find user
 (fn [[_ user-id]]
   (if user-id
     (rf/subscribe [:<-store :users user-id])
     (rf/subscribe [:nil]))) ; avoid nil input error haha
 (fn [user [_ user-id]]
   user)) ; here would merge in karma and whatnot

(rf/reg-sub :user/default-avatar
 :<- [:get :content :common :user-avatar-fallback]
 (fn [fallback [_ user-level]]
   fallback))


(rf/reg-sub :user/active-user
 :<- [:state [:active-user]]
 (fn [user [_ _]]
   user))

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

(rf/reg-sub :user/error ; login-error, rename...
 :<- [:diag/unhandled]
  (fn [unhandled [_ _]]
    (->> unhandled
         (filter #(= (:title %) "Sign in"))
         last
         :message)))
