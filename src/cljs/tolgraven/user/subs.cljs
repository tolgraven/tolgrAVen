(ns tolgraven.user.subs
  (:require
   [re-frame.core :as rf]))

(rf/reg-sub :user/users
 :<- [:get :users]
 :<- [:get :fb/users]
 (fn [[users fb-users] [_ path]]
   (get-in users path
           (get-in fb-users path))))

(rf/reg-sub :user/user ;find user
 :<- [:get :users]
 :<- [:get :fb/users]
 (fn [[users fb-users] [_ user-id]]
   (get fb-users user-id
        ; (first (filter #(= (:id %) user-id) users))
        )))


(rf/reg-sub :user/active-user
 :<- [:get]
 :<- [:state [:user]]
 (fn [[db user-id] [_ _]]
   (get-in db [:fb/users user-id])))

(rf/reg-sub :user/active-section
 :<- [:state [:user-section]]
 (fn [section [_ _]]
   section))

; (rf/reg-sub :user/status
;  (fn [db [_ user-id]]
;    (-> @(rf/subscribe [:user/user used-id]) :status)))

(rf/reg-sub :login/field
 :<- [:state [:login-field]]
 :<- [:state [:register-field]]
 (fn [[login register] [_ field]]
  (or (field login)
      (field register))))

(rf/reg-sub
 :login/valid-input?
 :<- [:form-field [:login]]
 (fn [login-field [_ field]]
   (and (pos? (count (:user     login-field)))
        (pos? (count (:password login-field)))))) ; do proper validation tho talk to server and greenlight when correc.
