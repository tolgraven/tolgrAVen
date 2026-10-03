(ns tolgraven.user.events
  (:require
   [re-frame.core :as rf]
   [re-frame.std-interceptors :refer [path]]
   ; [day8.re-frame.tracing :refer-macros [fn-traced]]
   [tolgraven.util :as util]
   [tolgraven.supabase.client :as supabase]
   [clojure.walk :as walk]
   [ajax.core :as ajax]))

(def debug (when ^boolean goog.DEBUG rf/debug))


(defn- auth-error! [error]
  (rf/dispatch [:supabase/auth-error error]))

(rf/reg-event-fx :user/create-account
  (fn [_ [_ email password]]
    (supabase/sign-up! email password
      #(when-not % (rf/dispatch [:diag/new :info "Sign in" "Check your email to confirm your account."]))
      auth-error!)
    {}))

(rf/reg-event-fx :user/sign-in
  (fn [_ [_ method email password]]
    (supabase/sign-in! method email password auth-error!) {}))

(rf/reg-event-fx :user/sign-out
  (fn [_ _] (supabase/sign-out! auth-error!) {}))

(rf/reg-event-fx :user/request-login
  (fn [{:keys [db]} _]
    (let [{:keys [email password]} (get-in db [:state :form-field :login])]
      {:dispatch [:user/sign-in :email email password]})))

(rf/reg-event-fx :user/login [debug]
(fn [{:keys [db]} [_ user-id]]
  (let [auto-open? (get-in db [:options :user :auto-open?])] ;would also need to update url tho per current way of doing things, to keep consistent...
    (merge
     {:db (assoc-in db [:state :user] user-id)}
     (if auto-open?
       {:dispatch [:user/request-open-ui]}
       {:dispatch [:user/attempt-page :admin]})))))

(rf/reg-event-fx :user/logout 
(fn [{:keys [db]} [_ user]]
  {:db (-> db (update-in [:state] dissoc :user)
              (update-in [:state] dissoc :active-user)
)
   :dispatch [:user/request-close-ui]}))


(rf/reg-event-fx :user/request-register
  (fn [{:keys [db]} _]
    (let [{:keys [email password]} (get-in db [:state :form-field :login])]
      ;; Enter the account page only after Auth supplies a verified session.
      {:dispatch [:user/create-account email password]})))

(rf/reg-event-fx :user/request-page ; go to userbox page, opening if not already
 (fn [{:keys [db]} [_ ]]
   (let [user (get-in db [:state :active-user])]
     {:dispatch (if user
                  [:user/active-section :admin :force]
                  [:user/active-section :login :force])})))

(rf/reg-event-fx :user/attempt-page ; same as above but don't open unless already...
 (fn [{:keys [db]} [_ ]]
   (let [user (get-in db [:state :active-user])
         user-section (get-in db [:state :user-section])]
     {:dispatch (if user
                  [:user/active-section :admin]
                  [:user/active-section :login])})))

(rf/reg-event-db :user/active-section
 (fn [db [_ v force?]]
   (if (or force? (= v :closed))
       (assoc-in db [:state :user-section] [v])
       (update-in db [:state :user-section] (comp vec conj) v)))) ;tho might wanna push closed as well then check alsewhere when reopen whether pos then pop/disj :closed...

(rf/reg-event-db :user/to-last-section
 (fn [db [_ _]]
   (update-in db [:state :user-section] pop)))

(rf/reg-event-fx :user/close-ui ;needs to defer changing :user-section to false
 (fn [{:keys [db]} [_ ]]
   {:dispatch [:user/active-section :closing]
    :dispatch-later {:ms 1000,
                     :dispatch [:user/active-section :closed]}}))

(rf/reg-event-fx :user/open-ui ;needs to defer changing :user-section to false
 (fn [{:keys [db]} [_ page]]
   {:dispatch [:user/active-section :closing]
    :dispatch-later {:ms 5,
                     :dispatch (if page
                                 [:user/active-section page :force]
                                 [:user/request-page])}}))

(rf/reg-event-fx :user/request-close-ui ;just updates query like
 (fn [{:keys [db]} [_ ]]
   {:dispatch [:href/update-current {:query {:userBox "false"}}] }))

(rf/reg-event-fx :user/request-open-ui ;same
 (fn [{:keys [db]} [_ page]]
   {:dispatch [:href/update-current {:query {:userBox "true"}}] }))

(rf/reg-event-fx :user/upload-avatar
  (fn [_ [_ file]]
    (supabase/authenticated-request! :post "/api/supabase/avatar"
      (doto (js/FormData.) (.append "file" file))
      #(rf/dispatch [:supabase/profile %]) auth-error!)
    {}))

(rf/reg-event-fx :user/set-field
  (fn [_ [_ _ field value]]
    (supabase/authenticated-request! :put "/api/supabase/profile" {field value}
      #(rf/dispatch [:supabase/profile %]) auth-error!)
    {}))

(rf/reg-event-fx :user/request-change-password
  (fn [{:keys [db]} _]
    (let [{:keys [current new]} (get-in db [:state :form-field :change-password])]
      (supabase/change-password! current new
        #(rf/dispatch [:user/password-changed]) auth-error!)
      {})))

(rf/reg-event-fx :user/password-changed
  (fn [{:keys [db]} _]
    {:db (update-in db [:state :form-field] dissoc :change-password)
     :dispatch [:diag/new :info "Password changed" "Your new password is saved."]}))
