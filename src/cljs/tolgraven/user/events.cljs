(ns tolgraven.user.events
  (:require
   [re-frame.core :as rf]
   [re-frame.std-interceptors :refer [path]]
   [tolgraven.util :as util]))


(defn- get-user
  [user users]
  (first (filter #(= (:name %) user) users)))

(rf/reg-event-fx
 :user/request-login ; will evt just http-post, on-success will handle rest incl login
 (fn [{:keys [db]} [_ info]]
   (let [login (-> db :state :login-field)
         user (get-user (:user login) (-> db :users))]
     (if (and (pos? (count (:user login)))
              (= (:password login) (:password user)))
       {:dispatch-n [[:user/login (:user login)]
                     [:user/active-section :admin :force]]}
       {:dispatch [:diag/new :error "Sign in" "failed validation"]}))))
   ; {:dispatch [:http-post ]}))

(rf/reg-event-db :user/login 
(fn [db [_ user]]
  (assoc-in db [:state :user] user)))

(rf/reg-event-db :user/logout 
(fn [db [_ user]]
  (-> db
      (update-in [:state] dissoc :user)
      (assoc-in [:state :user-section] [:closed]))))


(rf/reg-event-fx
 :user/request-register [(path [:state])]
 (fn [{:keys [db]} [_ info]]
   (let [{:keys [user password]} (:login-field db)
         {:keys [email]} (:register-field db)]
   {:dispatch-n [[:user/register user password email]
                 [:user/login user password]
                 [:user/active-section :admin :force]]})))

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
                  [:user/active-section :admin :force]
                  [:user/active-section :login :force])})))


(rf/reg-event-db
 :user/active-section
 (fn [db [_ v force?]]
   (if (or force? (= v :closed))
       (assoc-in db [:state :user-section] [v])
       (update-in db [:state :user-section] (comp vec conj) v)))) ;tho might wanna push closed as well then check alsewhere when reopen whether pos then pop/disj :closed...

(rf/reg-event-db
 :user/to-last-section
 (fn [db [_ _]]
   (update-in db [:state :user-section] pop)))

(rf/reg-event-fx ;needs to defer changing :user-section to false
 :user/close-ui
 (fn [{:keys [db]} [_ v]]
   {:dispatch [:user/active-section :closing]
    :dispatch-later {:ms 400,
                     :dispatch [:user/active-section :closed]}}))
