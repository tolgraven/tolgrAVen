(ns website.modules.github.module
  (:require
    [website.react :as r]
    [website.modules.github.storage :as storage]
    [website.modules.github.configuration-schema :as configuration-schema]
    [website.components.activity-schema :as activity-schema]
    [website.deployment.schema :as deployment-schema]
    [website.modules.github.events]
    [website.modules.github.subs]
    [website.modules.github.views :as view]))

(def spec
  ;; This section is not published by the CMS yet. Keep the optional view
  ;; available without adding an unsupported key to bootstrap/module requests.
  {:content []
   :depends [{:source :url
              :url "/api/github/settings"
              :into [:module :github :configuration]}]
   :server {:routes 'website.modules.github.api/routes
            :start 'website.modules.github.sync/start!}
   :storage storage/tables
   :db-schema {[:module :github :configuration] configuration-schema/result
               [:module :github :activity] [:map-of :string activity-schema/result]
               [:module :github :deployment] deployment-schema/metadata
               [:module :github :days] [:map-of :string [:map-of :string activity-schema/breakdown]]}
   :view {:view #'view/<commits>}
   :init #(r/dispatch [:github/init])})
