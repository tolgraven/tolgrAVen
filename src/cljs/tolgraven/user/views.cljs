(ns tolgraven.user.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.util :as util :refer [at]]
   [tolgraven.ui :as ui]))


(defn sign-in "Sign in component"
  []
  [:section>form
   [:input {:class "input-dark" :placeholder "Username"
            :value @(rf/subscribe [:state [:login-field :user]])
            :on-change #(rf/dispatch [:state [:login-field :user] (-> % .-target .-value)])}]
   [:span "error"]
   [:br]
   
   [:input {:type :password 
            :class "input-dark" :placeholder "Password"
            :value @(rf/subscribe [:state [:login-field :password]])
            :on-change #(rf/dispatch [:state [:login-field :password] (-> % .-target .-value)])}] ])


(defn sign-in-or "Sign in or go to reg page"
  []
  [:div.user-forms.noborder
   [:h2 "You must be logged in"]
   [sign-in]
   [:button
    {:on-click #(rf/dispatch [:user/request-login])}
    "Sign in"]
   [:span "or "]
   [:button
    {:on-click #(rf/dispatch [:state [:user-section] :register])}
    "Register"]])

(defn register "Registration component" []
  [:div.user-forms.user-register
   [:button {:on-click #(rf/dispatch [:state [:user-section] :login])
             :style {:position :absolute :left 0 :top 0}} "<"]
   [:h2 "Register"]
   [sign-in] ;well need different validation here (not exists etc)
   [:input
    {:class "input-dark" :placeholder "Email"
     :value @(rf/subscribe [:state [:register-field :email]])
     :on-change #(rf/dispatch [:state [:register-field :email] (-> % .-target .-value)])}]
   [:br]
   [:button
    {:on-click #(rf/dispatch [:user/request-register])}
    "Sign up"] ])
;    
(defn profile "User profile page" []
  [:div "USER PROFILE"])

(defn admin "User admin page" []
  (let [user @(rf/subscribe [:user/active-user-details])]
    [:div
     [:div "USER ADMIN"]
     [:h3 (:name user)]
     [:span [:em (:email user)]] [:br]
     [:button "Change password"]
     [:button "View comments"]
     [:button {:on-click #(rf/dispatch [:user/logout])} "Log out"]
     ]))

(defn user-box "Wrapper for user views"
  [component]
  [:section.noborder
    [ui/close #(rf/dispatch [:state [:user-section] false])]
    [component]])
