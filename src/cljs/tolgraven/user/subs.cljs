(ns tolgraven.user.subs
  (:require
   [re-frame.core :as rf]
   [clojure.walk :as walk]))

(rf/reg-sub :user/users
 :<- [:get :users]
 :<- [:get :fb/users]
 (fn [[users fb-users] [_ path]]
   (get-in users path
           (get-in fb-users path))))

(rf/reg-sub :user/user ;find user
 :<- [:get :fb/users]
 (fn [fb-users [_ user-id]]
   (when user-id
     (get fb-users user-id
          (-> @(rf/subscribe [:firestore/on-snapshot
                              {:path-document [:users user-id]}])
              :data
              walk/keywordize-keys)))))

(rf/reg-sub :user/default-avatar
 :<- [:get :content :common :user-avatar-fallback]
 (fn [fallback [_ user-level]]
   fallback))


(rf/reg-sub :user/active-user
 :<- [:state [:user]]
 (fn [user-id [_ _]]
   @(rf/subscribe [:user/user user-id])))

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

(rf/reg-sub :user/error
 :<- [:diag/unhandled]
  (fn [unhandled [_ _]]
    (->> unhandled
         (filter #(= (:title %) "Sign in"))
         last
         :message)))
