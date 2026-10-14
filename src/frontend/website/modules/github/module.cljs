(ns website.modules.github.module
  (:require
    [website.react :as r]
    [website.modules.github.storage :as storage]
    [website.components.activity-schema :as activity-schema]
    [website.deployment.schema :as deployment-schema]
    [website.modules.github.events]
    [website.modules.github.subs]
    [website.modules.github.views :as view]))

(def spec
  {:content [:github-settings]
   :server {:routes 'website.modules.github.api/routes
            :start 'website.modules.github.sync/start!}
   :storage storage/tables
   :db-schema {[:module :github :activity] [:map-of :string activity-schema/result]
               [:module :github :deployment] deployment-schema/metadata
               [:module :github :days] [:map-of :string [:map-of :string activity-schema/breakdown]]}
   :view {:view #'view/<commits>}
   :init #(r/dispatch [:github/init])})
