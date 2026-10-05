(ns tolgraven.user.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.user.events]
    [tolgraven.user.subs]
    [tolgraven.user.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :user [])
   :id :user
   :view {:view #'view/<user-section>
          :btn #'view/<user-btn>
          :avatar #'view/<user-avatar>}})
