(ns tolgraven.user.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.util :as util :refer [at]]
   [tolgraven.ui :as ui]))

(defn back-btn [] ;tho ideally we push states and pop them... so becomes, yeah
  (when (< 1 (count @(rf/subscribe [:user/active-section])))
    [:button.user-back-btn.showing
     {:on-click #(rf/dispatch [:user/to-last-section])
      :style {:position :absolute :left 0 :top 0}} "<"]))

(defn password-input [& {:keys [placeholder path]
                         :or {placeholder "Password"
                              path [:form-field [:login :password]]}}]
  [ui/input-text
    :type (when-not @(rf/subscribe [:state [:login-show-password]]) :password)
    :placeholder placeholder
    :attr {:autoComplete "password"}
    :path path
    ; :on-change #(rf/dispatch [:form-field [:login :password] %])])
    :on-change #(rf/dispatch (into path [%]))])

(defn sign-in-input "Sign in component"
  []
  [:section>form
   
   [ui/input-text
    :placeholder "Username"
    :path [:form-field [:login :user]]
    :on-change #(rf/dispatch [:form-field [:login :user] %])]
   [:br]
   
   [password-input]
   [ui/toggle [:state :login-show-password] "show"]])


(defn sign-in "Sign in or go to reg page"
  []
  [:div.user-inner.noborder
   [:h2 "Please log in"]
   [sign-in-input]
   (when-let [error (->> @(rf/subscribe [:diag/unhandled])
                         (filter #(= (:title %) "Sign in"))
                         last
                         :message)] ;should be a sub
     [:<>
      [:span {:style {:padding-top "0em" :color "var(--red)"}} "Error"]
     [:span ": " error] [:br]])
   ; [:br]
   [:button
    (let [disabled? (not @(rf/subscribe [:login/valid-input?]))]
      {:on-click #(rf/dispatch [:user/request-login])
       :disabled disabled?
       :class (when disabled? "noborder")})
    "Sign in"]    [:span "or "]
   [:button {:on-click #(rf/dispatch [:user/active-section :register])}
    "Register"]   [:span "or "]
   [:button {:on-click #(rf/dispatch [:fb/sign-in :google])}
    "Sign in with Google"]
   
   ])

(defn register "Registration component" []
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
;    
(defn profile "User profile page" []
  [:div "USER PROFILE"])
(defn comments "User comments page" []
  [:div.user-inner
    [:h2 "User comments"]
   [:section "all of them"]]) ;impl ugly hack or defer til db?

(defn change-password "Change user password" []
  [:div.user-inner
    [:h2 "Change password"]
    [:section
     [password-input :placeholder "Current password"
                     :path [:form-field [:change-password :current]]]
     [password-input :placeholder "New password"
                     :path [:form-field [:change-password :new]]] 
     [ui/toggle [:state :login-show-password] "show"]
     [:button
      {:on-click #(rf/dispatch [:user/request-change-password])}
      "Change password"] ]])

(defn change-username "Change username" []
  )

(defn admin "User admin page" []
  (let [user @(rf/subscribe [:user/active-user])
        section-btn (fn [text k section]
                      [ui/button text k
                                 :action #(rf/dispatch [:user/active-section section])])]
    [:div.user-inner
     [:section
      [:div.flex
       [:img.user-avatar {:src (:avatar user)}]
       [:div
        [:h3 (:name user)]
        [:span [:em (:email user)]]
        [:br] [:br]
        [:span (str "n" " comments")]
        [section-btn "View all" :comments :comments]]]]
     
      [section-btn "Change username"  :username :change-username]
      [section-btn "Change password"  :password :change-password]
      
      (when true ;(some #{:blogger} (:roles user))
        [ui/button "Post blog"      :post-blog :link "#/post-blog" ])

      [ui/button "Log out" :logout  :action #(rf/dispatch [:fb/sign-out])] ]))


(defn user-box "Wrapper for user views"
  [component]
  [:section.noborder
   [back-btn]
   [ui/close #(rf/dispatch [:user/close-ui])]
   [component]])

(defn user-section
  [active-section]
  [:div.user-section-wrapper.stick-up.hi-z
   {:class (when (and (some? active-section)
                      (not (some #{:closing :closed} active-section)))
             "active")}
   (when (and (seq active-section) (not (some #{:closed} active-section)))
     [:div.user-section
      (let [active-section (case (last active-section)
                             :closing (or (last (butlast active-section))
                                          :login) ;patch through underlying
                             (last active-section))]
      [user-box
       (case active-section ;get last in list. guess should hook up to proper router though.
         :login        sign-in
         :register     register
         :admin        admin
         :comments     comments
         :change-password change-password
         :change-username change-username)])
      ]) ])
