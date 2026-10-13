(ns website.modules.docs.module
  (:require
    [website.modules.docs.pages :as pages]
    [website.content.contract :as content-contract]
    [website.react :as r]
    [website.diagnostics.docs :as development]
    [website.modules.docs.events]
    [website.modules.docs.subs]
    [website.modules.docs.views :as view]))

(def spec
  {:content [:docs]
   :server {:routes 'website.modules.docs.api/routes}
   :depends (get content-contract/module-dependencies :docs [])
   :id :docs
   :styles ["/css/website/modules/docs.min.css"]
   :pages pages/spec
   :view (merge {:page #'view/<page>} development/views)
   :init #(r/dispatch [:docs/init])})
