(ns tolgraven.user.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ; [re-graph.core :as rg]
   ; [markdown.core :refer [md->html]]
   [clojure.string :as string]
   [tolgraven.util :as util :refer [at]]
   [tolgraven.ui :as ui]))

(defn input "Wrap input-text"
  [kind path on-change]
  [ui/input-text
   :class "input-dark" :placeholder (name kind)
      ; :model     (rf/subscribe [:register/field :email])
   :path path :on-change on-change ])
      ; :on-enter #(rf/dispatch [:state :filter :save %]) ;well no

(defn sign-in "Sign in component"
  []
  [:section
   [:input {:class "input-dark" :placeholder "Username"
            :on-change #(rf/dispatch [:state [:register :name] (-> % .-target .-value)])}]
   
   [:br]
   [:input {:type :password 
            :class "input-dark" :placeholder "Password"
            :on-change #(rf/dispatch [:state [:register :password] (-> % .-target .-value)])}] ])

(declare register)

(defn sign-in-or "Sign in or go to reg page"
  []
  [:section.user-sign-in.solid-bg
   [ui/close #(rf/dispatch [:state [:modal] false])]
   [:h2 "You must be logged in"]
   [sign-in]
   [:button
    {:on-click #(rf/dispatch [:user/request-login])}
    "Sign in"]
   [:span "or "]
   [:button
    {:on-click #(rf/dispatch [:state [:modal] :register])}
    "Register"]]
   )

(defn user-profile "User profile page" []
  [:div])

(defn user-admin "User admin page" [])

(defn register "Registration component" []
  [:section.user-register.solid.bg
   [:h2 "Register"]
   [:br]
   [input :email [:register/field :email]
    #(rf/dispatch [:state [:register :email] %])]
   
   ])
; [ui/input-text :class "input-dark" :placeholder "filter..."
;    :path [:get :filter :value] ;ahh wait this way no deref here = wont rerender further down... never gets called and given chance to prove it will diff
;    :on-change (db/setter [:filter :value]) :change-on-blur? false
;    :on-enter #(rf/dispatch [:filter :save %])]
