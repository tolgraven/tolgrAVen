(ns tolgraven.user.events
  (:require
   [re-frame.core :as rf]
   [re-frame.std-interceptors :refer [path]]
   ; [day8.re-frame.tracing :refer-macros [fn-traced]]
   [tolgraven.util :as util]
   [ajax.core :as ajax]))

(def debug (when ^boolean goog.DEBUG rf/debug))


(rf/reg-event-fx :fb/finish-sign-in [(rf/inject-cofx :user/gen-color)
                                     (rf/inject-cofx :gen-id [:user])]
 (fn [{:keys [db bg-color id]} [_ user]]
   (let [user-map {:name (or (:display-name user) (:email user))
                   :email (:email user)
                   :avatar (:photo-url user)
                   :bg-color bg-color
                   :id (:uid user)
                   :seq-id (-> id :id :user)}]
     {:db (update-in db [:fb/users (:uid user)]
                     #(merge %2 %1)
                     user-map)
      :dispatch [:user/active-section :admin :force]
      :firestore/set {:path [:users (:uid user)]
                      :data user-map}
      :firebase/set {:path [:users (:uid user)]
                      :data user-map}})))

(rf/reg-event-fx :fb/set-user [debug]
  (fn [{:keys [db]} [_ user]]
    (when (some? user)
     {:dispatch-n [[:state [:firebase :user] user]
                   [:fb/finish-sign-in user]
                   [:user/login (:uid user)]]})))


(rf/reg-event-fx :fb/fetch-users
  (fn [{:keys [db]} [_ user]]
    {:firestore/get {:path-collection :users ; [users] simply wont work. says it fails bc is nil but makes even less sense.
                     :on-success [:fb/store-users]}}))
(rf/reg-event-db :fb/store-users
  (fn [db [_ users]]
    (assoc-in db :fb/users users)))


(rf/reg-event-fx :fb/error
  (fn [{:keys [db]} [_ error]]
    {:dispatch [:diag/new :error "Firebase" error]}))

(rf/reg-event-fx :fb/create-user [debug]
 (fn [_ [_ email password]]
  {:firebase/email-create-user {:email email :password password}}))

(rf/reg-event-fx :fb/sign-in ;; Simple sign-in event. Just trampoline down to the re-frame-firebase fx handler.
 (fn [_ [_ method & [email password]]]
   (case method
     :google {:firebase/google-sign-in {:sign-in-method :popup}} ;TODO use redir instead but save entire state to localstore inbetween.
     :email  {:firebase/email-sign-in {:email email :password password}})))

(rf/reg-event-fx :fb/sign-out ;;; Ditto for sign-out
 (fn [_ _]
   {:firebase/sign-out nil
    :dispatch [:user/logout]}))


(rf/reg-event-fx
 :user/fetch-users ; fetch all reasonable enough up to a few k regged I guess? scaling obvs = pass ids from posts, comments
 (fn [{:keys [db]} [_ uids]]
   {:firestore/get {:path [:users]}}))

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
  {:db (assoc-in db [:state :user] user)
   :dispatch [:user/active-section :admin :force]}))

(rf/reg-event-fx :user/logout 
(fn [{:keys [db]} [_ user]]
  {:db (update-in db [:state] dissoc :user)
   :dispatch [:user/close-ui]}))


(rf/reg-event-fx
 :user/request-register [(path [:state])]
 (fn [{:keys [db]} [_ info]]
   (let [{:keys [email password]} (-> db :form-field :login) ]
   {:dispatch-n [[:fb/create-user email password]
                 [:user/active-section :admin :force]]})))

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
 (fn [{:keys [db]} [_ ]]
   {:dispatch [:user/active-section :closing]
    :dispatch-later {:ms 1000,
                     :dispatch [:user/active-section :closed]}}))

(rf/reg-event-fx ;needs to defer changing :user-section to false
 :user/open-ui
 (fn [{:keys [db]} [_ page]]
   {:dispatch [:user/active-section :closing]
    :dispatch-later {:ms 100,
                     :dispatch (if page
                                 [:user/active-section page]
                                 [:user/request-page])}}))

(rf/reg-event-fx
 :user/upload-avatar ; save new avatar upload. So upload to server, get filename, use it to update user in db and store
 (fn [{:keys [db]} [_ file]] ;also inject active-user here, use for filename
   {:dispatch
    [:http/post {:uri "/api/files/upload" ;could also upload to firebase. not sure if implemented in re-frame-firebase tho
                 :format (ajax/text-request-format)
                 :body (doto
                           (js/FormData.)
                           (.append "id" "10")
                           (.append "file" file "avatar.png")) ;but would want (need! for extension lol) to extract the thing yo
                 :on-success [:user/save-avatar]}] }))
(rf/reg-event-fx
 :user/save-avatar [debug] ;[(rf/inject-cofx :inject/inject [:user/active-user])]
 (fn [{:keys [db inject]} [_ path]]
   {:db (assoc-in db [:fb-users (:id inject) :avatar] path)
    :dispatch [:diag/new :warning "File upload" "Your avatar reached the server!\n ...but was not saved since that's not implemented."]
    ; [:store-> [:users (:id inject)]
    ;                     {:avatar path}
    ;                     [:avatar]]
    }))
