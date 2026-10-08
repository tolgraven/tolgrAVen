(ns tolgraven.modules.user.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.modules.user.events]
    [tolgraven.modules.user.subs]
    [tolgraven.modules.user.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :user [])
   :id :user
   :view {:view #'view/<user-section>
          :btn #'view/<user-btn>
          :avatar #'view/<user-avatar>}})
