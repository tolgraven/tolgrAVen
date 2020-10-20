(ns tolgraven.user.subs
  (:require
   [re-frame.core :as rf]))

(rf/reg-sub :user/users
 :<- [:get :users]
 (fn [users [_ path]]
   (get-in users path)))

(rf/reg-sub :user/user ;find user
 :<- [:get :users]
 (fn [users [_ user]]
   (first (filter #(= (:name %) user) users))))

(rf/reg-sub :user/active-user-name
 :<- [:state [:user]]
 (fn [user [_ _]]
   user))
(rf/reg-sub :user/active-user-details
 :<- [:state [:user]]
 (fn [user [_ _]]
   @(rf/subscribe [:user/user user])))

(rf/reg-sub :user/session
 :<- [:state [:user]]
 (fn [user [_ _]]
   (-> user :session)))

(rf/reg-sub :user/active-section
 :<- [:state [:user-section]]
 (fn [section [_ _]]
   section))
; (rf/reg-sub :user/status
;  :<- [:user/session]
;  (fn [session [_ _]]
;    (-> session :status)))

(rf/reg-sub :login/field
 :<- [:state [:login-field]]
 :<- [:state [:register-field]]
 (fn [[login register] [_ field]]
  (or (field login)
      (field register))))

(rf/reg-sub
 :login/valid-input?
 :<- [:state [:login-field]]
 (fn [login-field [_ field]]
   (and (pos? (count (:user login-field)))
        (pos? (count (:user login-field)))))) ; do proper validation tho talk to server and greenlight when correc.
