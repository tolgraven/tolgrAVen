(ns tolgraven.modules.link-preview.module
  {:bundle/depends-on #{:main :markdown}}
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.modules.link-preview.events]
    [tolgraven.modules.link-preview.subs]
    [tolgraven.modules.link-preview.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :link-preview [])
   :id :link-preview
   :ssr-styles :deferred
   :styles ["/css/tolgraven/modules/link-preview.min.css"]
   :view {:view #'view/<link-preview>
          :md #'view/<md>}})
