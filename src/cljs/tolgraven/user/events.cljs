(ns tolgraven.user.events
  (:require
   [re-frame.core :as rf]
   [re-frame.std-interceptors :refer [path]]
   [tolgraven.util :as util]))


(defn- get-user
  [user users]
  (first (filter #(= (:name %) user) users)))

(rf/reg-event-fx
 :user/request-login
 (fn [{:keys [db]} [_ info]]
   (let [login (-> db :state :login-field)
         user (get-user (:user login) (-> db :users))]
     (when (= (:password login)
              (:password user))
       {:dispatch-n [[:user/login (:user login)]
                     [:state [:user-section] :admin]]}))))
   ; {:dispatch [:http-post ]}))

(rf/reg-event-db :user/login 
(fn [db [_ user]]
  (assoc-in db [:state :user] user)))

(rf/reg-event-db :user/logout 
(fn [db [_ user]]
  (-> db
      (update-in [:state] dissoc :user)
      (assoc-in [:state :user-section] false))))


(rf/reg-event-fx
 :user/request-register [(path [:state])]
 (fn [{:keys [db]} [_ info]]
   (let [{:keys [user password]} (:login-field db)
         {:keys [email]} (:register-field db)]
   {:dispatch-n [[:user/register user password email]
                 [:user/login user password]
                 [:state [:user-section] :admin]]})))

(rf/reg-event-db :user/register
 (fn [db [_ user password email]]
   (update-in db [:users] conj {:name user
                                :password password
                                :email email})))

(rf/reg-event-fx
 :user/request-page
 (fn [{:keys [db]} [_ info]]
   (let [user (get-in db [:state :user])]
     {:dispatch (if user
                  [:state [:user-section] :admin]
                  [:state [:user-section] :login])})))

