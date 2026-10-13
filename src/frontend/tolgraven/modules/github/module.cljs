(ns tolgraven.modules.github.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.components.activity-schema :as activity-schema]
    [tolgraven.deployment.schema :as deployment-schema]
    [tolgraven.modules.github.events]
    [tolgraven.modules.github.subs]
    [tolgraven.modules.github.views :as view]))

(def spec
  {:content [:github-settings]
   :server {:routes 'tolgraven.modules.github.api/routes}
   :depends (get content-contract/module-dependencies :github [])
   :db-schema {[:module :github :activity] [:map-of :string activity-schema/result]
               [:module :github :deployment] deployment-schema/metadata}
   :id :github
   :styles ["/css/tolgraven/modules/github.min.css"]
   :view {:view #'view/<commits>}
   :init #(rf/dispatch [:github/init])})
