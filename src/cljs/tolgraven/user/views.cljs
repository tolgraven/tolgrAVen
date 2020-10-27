(ns tolgraven.user.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.util :as util :refer [at]]
   [tolgraven.ui :as ui]))

(defn back-btn [] ;tho ideally we push states and pop them... so becomes, yeah
  (when (< 1 (count @(rf/subscribe [:user/active-section])))
    [:button.user-back-btn.noborder.showing
     {:on-click #(rf/dispatch [:user/to-last-section])
      :style {:position :absolute :left 0 :top 0}}
     [:i.fa.fa-chevron-left]]))

(defn password-input [& {:keys [placeholder path]
                         :or {placeholder "Password"
                              path [:form-field [:login :password]]}}]
  [ui/input-text
    :type (when-not @(rf/subscribe [:state [:login-show-password]]) :password)
    :placeholder placeholder
    :attr {:autoComplete "password"}
    :path path
    :on-change #(rf/dispatch (into path [%]))])

(defn sign-in-input "Sign in component" []
  [:section>form
   [ui/input-text
    :placeholder "Email"
    :attr {:autoComplete "email"}
    :path [:form-field [:login :email]]
    :on-change #(rf/dispatch [:form-field [:login :email] %])]
   [:br]
   [password-input]
   [ui/toggle [:state :login-show-password] "show"]])


(defn sign-in "Sign in or go to reg page" []
  (let [disabled? (not @(rf/subscribe [:login/valid-input?]))]
    [:div.user-inner.noborder
     [:h2 "Please log in"]
     [sign-in-input]
     
     (when-let [error @(rf/subscribe [:user/error])] ;should be a sub
       [:<>
        [:span {:style {:padding-top "0em" :color "var(--red)"}} "Error"]
        [:span ": " error] [:br]])
     
     [:button
      {:on-click #(rf/dispatch [:user/request-login])
       :disabled disabled?
       :class (when disabled? "noborder")}
      "Sign in"]    [:span "or "]
     
     [:button {:on-click #(rf/dispatch [:user/request-register])
               :disabled disabled?
               :class (when disabled? "noborder")}
      "Register"]   [:span "or "]
     
     [:button {:on-click #(rf/dispatch [:fb/sign-in :google])}
      "Sign in with Google"] ]))

(defn register "Registration component" [user]
  [:div.user-inner.user-register
   [:h2 "Register"]
   [sign-in-input] ;well need different validation here (not exists etc)
   [ui/input-text
    :path [:form-field [:register :email]]
    :placeholder "Email"
    :on-change #(rf/dispatch [:form-field [:register :email] %])]
   
   ; [:br]
   [:button
    {:on-click #(rf/dispatch [:user/request-register])}
    "Sign up"] ])

(defn profile "User profile page" [user-id]
  [:div "USER PROFILE"])

(defn section "Wrap thing in user-inner etc"
  [heading & components]
  [:div.user-inner
   (when heading [:h2 heading])
   (into [:section] components)])

(defn comments "User comments page" [user]
  [section "User comments" ;will need to save comment id's to user when make new ones.
   (let []
     [:div])
   ])

(defn change-password "Change user password" [user]
  [section "Change password"
   [:form
    [password-input :placeholder "Current password"
     :path [:form-field [:change-password :current]]]
    [password-input :placeholder "New password"
     :path [:form-field [:change-password :new]]] 
    [ui/toggle [:state :login-show-password] "show"]]
   [:br]
   [:button
    {:on-click #(rf/dispatch [:user/request-change-password])}
    "Change password"] ])

(defn change-username "Change username" [user]
  [section "Change username"
   [:p "Current username: " (:name user)]
   [ui/input-text
    :path [:form-field [:change :username]]
    :placeholder "New username"
    :on-change #(rf/dispatch [:form-field [:change :username] %])]
   [ui/button "Change" :change-username
              :action #(rf/dispatch [:user/set-field
                                     (:id user) :name
                                     @(rf/subscribe [:form-field [:change :username]])])]])
(declare avatar)
(defn change-avatar "Change avatar" [user]
  [section
   "Upload profile picture" 
   [:br]
   [avatar user false]
   [:br]
   [:span "Upload file "]
   [:input {:type "file" :id "file" :name "file" 
            :on-change 
            #(rf/dispatch [:user/upload-avatar
                           (-> % .-target .-files (aget 0))])}]
   [:br]
   [:span "Or from url "]
   [ui/input-text
    :path [:form-field [:change :avatar-url]]
    :placeholder "URL"
    :on-change (fn [e] (rf/dispatch [:form-field [:change :avatar-url] e]))]
   [ui/button "Change" :change-avatar-url
    :action #(rf/dispatch [:user/set-field (:id user) :avatar
                                          @(rf/subscribe [:form-field [:change :avatar-url]])])]])

(defn avatar "Display user avatar and option to change it"
  [user-map allow-edit?]
  [:div.user-avatar-wrapper
   (when allow-edit?
     [:div.user-avatar-change
      [:i.fa.fa-edit {:on-click #(rf/dispatch [:user/active-section :change-avatar])}]])
   [:img.user-avatar
    {:src (or (:avatar user-map)
              @(rf/subscribe [:user/default-avatar]))
     :alt "User profile picture"}]])

(defn admin "User admin page" [user]
  (let [roles @(rf/subscribe [:<-store :auth :roles])
        section-btn (fn [text k section]
                      [ui/button text k
                                 :action #(rf/dispatch [:user/active-section section])])]
    [:div.user-inner
     [:section
      [:div.flex
       [avatar user true]
       [:div
        [:h3 (:name user)]
        [:span [:em (:email user)]]
        [:br] [:br]
        [:span (str "n" " comments")]
        [section-btn "View all" :comments :comments]
        (when (some #{(:id user)} (:bloggers roles))
          [ui/button "Post blog" :post-blog :link "#/post-blog" ])
        (when (some #{(:id user)} (:admins roles))
          [ui/button "Site admin" :post-blog :link "#/site-admin"]) ]]]
     
     [:div.user-change-options
      [:span "Change: "]
      [section-btn "Username"  :username :change-username]
      [section-btn "Password"  :password :change-password] ; firebase has its own so should just put a link for reset-password email
      [section-btn "Picture"   :avatar   :change-avatar]]
      
      [ui/button "Log out" :logout  :action #(rf/dispatch [:fb/sign-out])] ]))


(defn user-box "Wrapper for user views"
  [user component]
  [:section.noborder
   [back-btn]
   [ui/close #(rf/dispatch [:user/close-ui])]
   [component user]])

(defn user-section
  [active-section]
  (let [user @(rf/subscribe [:user/active-user])]
    [:div.user-section-wrapper.stick-up.hi-z
     {:class (when (and (some? active-section)
                        (not (some #{:closing :closed} active-section)))
               "active")}
     (when (and (seq active-section)
                (not (some #{:closed} active-section)))
       [:div.user-section
        (let [active-section (case (last active-section)
                               :closing (or (last (butlast active-section))
                                            :login) ;patch through underlying
                               (last active-section))]
          [user-box user
           (case active-section ;get last in list. guess should hook up to proper router though.
             :login        sign-in
             :register     register
             :admin        admin
             :comments     comments
             :change-avatar change-avatar
             :change-password change-password
             :change-username change-username)])
        ]) ]))
