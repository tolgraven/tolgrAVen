(ns website.modules.user.module
  (:require
    [website.modules.user.storage :as storage]
    [website.modules.user.events]
    [website.modules.user.subs]
    [website.modules.user.views :as view]))

(def spec
  {:content [:common]
   :ssr-styles :deferred
   :server {:routes 'website.modules.user.api/routes
            :sql ["supabase/modules/avatars.sql"]}
   :storage storage/tables
   :view {:view #'view/<user-section>
          :btn #'view/<user-btn>
          :avatar #'view/<user-avatar>}})
