(ns tolgraven.user.module
  (:require
    [tolgraven.user.events]
    [tolgraven.user.subs]
    [tolgraven.user.views :as view]))

(def spec
  {:id :user
   :view {:view #'view/user-section
          :btn #'view/user-btn
          :avatar #'view/user-avatar}})
