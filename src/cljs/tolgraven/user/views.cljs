(ns tolgraven.user.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.util :as util :refer [at]]
   [tolgraven.ui :as ui]))

(defn back-btn [] ;tho ideally we push states and pop them... so becomes, yeah
  (when (< 1 (count @(rf/subscribe [:user/active-section])))
    [:button {:on-click #(rf/dispatch [:user/to-last-section])
              :style {:position :absolute :left 0 :top 0}} "<"]))

(defn password-input [& [placeholder-name]]
  [ui/input-text
    :type (when-not @(rf/subscribe [:state [:login-show-password]]) :password)
    :placeholder (or placeholder-name "Password")
    :attr {:autoComplete "password"}
    :path [:state [:login-field :password]]
    :on-change #(rf/dispatch [:state [:login-field :password] %])])

(defn sign-in-input "Sign in component"
  []
  [:section>form
   
   [ui/input-text
    :placeholder "Username"
    :path [:state [:login-field :user]]
    :on-change #(rf/dispatch [:state [:login-field :user] %])]
   [:br]
   
   [password-input]
   [:label [:input {:type "checkbox"
                    :on-click #(rf/dispatch [:toggle [:state :login-show-password]])}]
    "show"] ])


(defn sign-in-or "Sign in or go to reg page"
  []
  [:div.user-inner.noborder
   [:h2 "Please log in"]
   [sign-in-input]
   [:button
    (let [disabled? (not @(rf/subscribe [:login/valid-input?]))]
      {:on-click #(rf/dispatch [:user/request-login])
       :disabled disabled?
       :class (when disabled? "noborder")})
    "Sign in"]
   [:span "or "]
   [:button
    {:on-click #(rf/dispatch [:user/active-section :register])}
    "Register"]])

(defn register "Registration component" []
  [:div.user-inner.user-register
   [:h2 "Register"]
   [sign-in-input] ;well need different validation here (not exists etc)
   [ui/input-text
    :path [:state [:register-field :email]]
    :placeholder "Email"
    :on-change #(rf/dispatch [:state [:register-field :email] %])]
   
   [:br]
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
    (let [show-pw @(rf/subscribe [:state [:login-show-password]])]
      [:<>
       [password-input "Current password"]
       [password-input "New password"] 
       [:label [:input {:type "checkbox"
                        :on-click #(rf/dispatch [:toggle [:state :login-show-password]])}]
        "show"]
       #_[:button {:on-click #(rf/dispatch [:toggle [:state :login-show-password]])} ;this somehow reloads current page wtf?
        (if @(rf/subscribe [:state [:login-show-password]])
          "hide" "show") ]])
   [:button
    {:on-click #(rf/dispatch [:user/request-change-password])}
    "Change password"] ]])

(defn admin "User admin page" []
  (let [user @(rf/subscribe [:user/active-user-details])]
    [:div.user-inner
     [:h2 "User admin"]
     [:section
     [:h3 (:name user)]
     [:span [:em (:email user)]] [:br] [:br]
     [ui/button "Change password" :password
      :action #(rf/dispatch [:user/active-section :change-password])]
     [ui/button "View comments" :comments
      :action #(rf/dispatch [:user/active-section :comments])]
     [ui/button "Log out" :logout
      :action #(rf/dispatch [:user/logout])]
     ]]))

(defn user-box "Wrapper for user views"
  [component]
  [:section.noborder
   [back-btn]
    [ui/close #(rf/dispatch [:user/active-section :closed])]
    [component]])

(defn user-section
  [active-section]
  [:div.user-section-wrapper.stick-up.hi-z
   {:class (when (not= active-section :closing) "active")}
   (println active-section)
   (when (and (seq active-section) (not= (last active-section) :closed))
     [:div.user-section.stick-up.hi-z ;.noborder
      [user-box
       ; (case active-section ;guess should hook up to proper router though.
       (case (last active-section) ;get last in list. guess should hook up to proper router though.
         :login  sign-in-or
         :register register
         :admin admin
         :comments comments
         :change-password change-password
         :closing admin)]
      ]) ])
