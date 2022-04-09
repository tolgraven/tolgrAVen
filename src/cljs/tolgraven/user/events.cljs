(ns tolgraven.user.events
  (:require
   [re-frame.core :as rf]
   [re-frame.std-interceptors :refer [path]]
   ; [day8.re-frame.tracing :refer-macros [fn-traced]]
   [tolgraven.util :as util]
   [clojure.walk :as walk]
   [ajax.core :as ajax]))

(def debug (when ^boolean goog.DEBUG rf/debug))


(rf/reg-event-fx :fb/finish-sign-in [debug
                                     (rf/inject-cofx :user/gen-color)
                                     (rf/inject-cofx :gen-id [:user])]
 (fn [{:keys [db bg-color id]} [_ user]]
   (let [defaults {:name (or (:display-name user) (:email user))
                   :email (:email user)
                   :avatar (:photo-url user)
                   :bg-color bg-color
                   :id (:uid user)
                   :comment-count 0
                   :karma 0
                   :seq-id (-> id :id :user)} ;well not proper it runs each time...
         merged (merge defaults (get-in db [:fb/users (:uid user)]))]
     {:db (assoc-in db [:fb/users (:uid user)] merged)
      :dispatch [:store-> [:users (:uid user)] merged]}))) ; problem: overwrites any changed values on login. so def need fetch all users on boot...

(rf/reg-event-fx :fb/set-user [debug]
  (fn [{:keys [db]} [_ user]]
    (when (some? (:uid user)) ; sometimes called with empty map...
     {:db (assoc-in db [:state :firebase :user] user)
      :dispatch-n [[:user/login (:uid user)]]
      :dispatch-later
      {:ms 3000 ;ugly, but login event is dispatched so early in boot no time to receive updated user data from firestore...
                ; ok-ish since defering only the storing part and not sign-in itself.. and may receive subs in meantime for user data
                ; guess only really affects first time signer inner?
                ; or with the boot fx lib could queue it up have it run after store-users
                ; of course simply updating the user in firebase makes more sense ;)
                ; prob extend re-frame-firebase for bunch of such extra things.
                ; can call manually in meantime.
       :dispatch [:fb/finish-sign-in user]}})))
                   
(rf/reg-event-fx :fb/fetch-users ; try find and fix bug in re-frame-firebase that causes this
  (fn [{:keys [db]} [_ user]]
    {:firestore/get {:path-collection [:users]
                     :on-success [:fb/store-users]}}))

(rf/reg-event-db :fb/store-users [debug]
  (fn [db [_ response]]
    (assoc-in db [:fb/users]
              (util/normalize-firestore response))))

(rf/reg-event-fx :fb/create-user [debug]
 (fn [_ [_ email password]]
  {:firebase/email-create-user {:email email :password password}}))

(rf/reg-event-fx :fb/sign-in ;; Simple sign-in event. Just trampoline down to the re-frame-firebase fx handler.
 (fn [_ [_ method & [email password]]]
   (case method
     :google    {:firebase/google-sign-in   {:sign-in-method :popup}} ;TODO use redir instead but save entire state to localstore inbetween.
     :facebook  {:firebase/facebook-sign-in {:sign-in-method :popup}} ;TODO use redir instead but save entire state to localstore inbetween.
     :github    {:firebase/github-sign-in   {:sign-in-method :popup}} ;TODO use redir instead but save entire state to localstore inbetween.
     :email  {:firebase/email-sign-in {:email email :password password}})))

(rf/reg-event-fx :fb/sign-out ;;; Ditto for sign-out
 (fn [_ _]
   {:firebase/sign-out nil
    :dispatch [:user/logout]}))


(defn- get-user
  [user users]
  (first (filter #(= (:name %) user) users)))

(rf/reg-event-fx
 :user/request-login ; will evt just http-post, on-success will handle rest incl login
 (fn [{:keys [db]} [_ info]]
   (let [login (-> db :state :form-field :login)
         user (get-user (:user login) (-> db :users))]
     {:dispatch [:fb/sign-in :email (:email login) (:password login)]})))


(rf/reg-event-fx :user/login [debug]
(fn [{:keys [db]} [_ user]]
  (let [auto-open? (get-in db [:options :user :auto-open?])]
    (merge
     {:db (assoc-in db [:state :user] user)}
     (when auto-open?
       {:dispatch [:user/open-ui :admin]})))))

(rf/reg-event-fx :user/logout 
(fn [{:keys [db]} [_ user]]
  {:db (update-in db [:state] dissoc :user)
   :dispatch [:user/close-ui]}))


(rf/reg-event-fx :user/request-register [(path [:state])]
 (fn [{:keys [db]} [_ info]]
   (let [{:keys [email password]} (-> db :form-field :login) ]
   {:dispatch-n [[:fb/create-user email password]
                 [:user/active-section :admin :force]]})))

(rf/reg-event-fx :user/request-page
 (fn [{:keys [db]} [_ info]]
   (let [user (get-in db [:state :user])]
     {:dispatch (if user
                  [:user/active-section :admin :force]
                  [:user/active-section :login :force])})))


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

(rf/reg-event-fx :user/upload-avatar ; save new avatar upload. So upload to server, get filename, use it to update user in db and store
 (fn [{:keys [db]} [_ file]] ;also inject active-user here, use for filename
   (let [filename (str "avatar-" (get-in db [:state :user]) ".png")]
     {:dispatch
      [:http/post {:uri "/api/files/upload" ;could also upload to firebase. not sure if implemented in re-frame-firebase tho
                   :format (ajax/text-request-format)
                   :body (doto
                           (js/FormData.)
                           (.append "id" "10")
                           (.append "file" file filename)) ;but would want (need! for extension lol) to extract the thing yo
                   :on-success [:user/save-avatar filename]}] })))

(rf/reg-event-fx :user/save-avatar
 (fn [{:keys [db]} [_ filename]]
   {:dispatch [[:user/set-field (get-in db [:state :user])
                :avatar (str "img/uploads/" filename)]]}))

(rf/reg-event-fx :user/set-field
 (fn [{:keys [db]} [_ user field value]]
   {:db (assoc-in db [:fb/users user field] value)
    :dispatch [:store-> [:users user]
                        {field value}
                        [field]]}))
