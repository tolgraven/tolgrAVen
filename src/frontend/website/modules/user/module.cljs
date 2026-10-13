(ns website.modules.user.module
  {:bundle/depends-on #{:main}}
  (:require
    [website.modules.user.storage :as storage]
    [website.content.contract :as content-contract]
    [website.modules.user.events]
    [website.modules.user.subs]
    [website.modules.user.views :as view]))

(def spec
  {:content [:common]
   :server {:routes 'website.modules.user.api/routes
            :sql ["supabase/modules/avatars.sql"]}
   :storage storage/tables
   :depends (get content-contract/module-dependencies :user [])
   :id :user
   :styles ["/css/tolgraven/modules/user.min.css"]
   :view {:view #'view/<user-section>
          :btn #'view/<user-btn>
          :avatar #'view/<user-avatar>}})
