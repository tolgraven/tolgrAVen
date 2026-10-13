(ns website.modules.github.module
  (:require
    [website.content.contract :as content-contract]
    [website.react :as r]
    [website.components.activity-schema :as activity-schema]
    [website.deployment.schema :as deployment-schema]
    [website.modules.github.events]
    [website.modules.github.subs]
    [website.modules.github.views :as view]))

(def spec
  {:content [:github-settings]
   :server {:routes 'website.modules.github.api/routes}
   :depends (get content-contract/module-dependencies :github [])
   :db-schema {[:module :github :activity] [:map-of :string activity-schema/result]
               [:module :github :deployment] deployment-schema/metadata}
   :id :github
   :styles ["/css/website/modules/github.min.css"]
   :view {:view #'view/<commits>}
   :init #(r/dispatch [:github/init])})
