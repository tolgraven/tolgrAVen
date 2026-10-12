(ns tolgraven.modules.user.module
  {:bundle/depends-on #{:main}}
  (:require
    [tolgraven.modules.user.storage :as storage]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.modules.user.events]
    [tolgraven.modules.user.subs]
    [tolgraven.modules.user.views :as view]))

(def spec
  {:content [:common]
   :server {:routes 'tolgraven.modules.user.api/routes
            :sql ["supabase/modules/avatars.sql"]}
   :storage storage/tables
   :depends (get content-contract/module-dependencies :user [])
   :id :user
   :styles ["/css/tolgraven/modules/user.min.css"]
   :view {:view #'view/<user-section>
          :btn #'view/<user-btn>
          :avatar #'view/<user-avatar>}})
