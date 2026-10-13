(ns website.modules.link-preview.module
  {:bundle/depends-on #{:main :markdown}}
  (:require
    [website.content.contract :as content-contract]
    [website.modules.link-preview.events]
    [website.modules.link-preview.subs]
    [website.modules.link-preview.views :as view]))

(def spec
  {:server {:routes 'website.modules.link-preview.api/routes}
   :depends (get content-contract/module-dependencies :link-preview [])
   :id :link-preview
   :ssr-styles :deferred
   :styles ["/css/tolgraven/modules/link-preview.min.css"]
   :view {:view #'view/<link-preview>
          :md #'view/<md>}})
