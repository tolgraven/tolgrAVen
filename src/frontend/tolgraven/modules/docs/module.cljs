(ns tolgraven.modules.docs.module
  (:require
    [tolgraven.modules.docs.pages :as pages]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as r]
    [tolgraven.diagnostics.docs :as development]
    [tolgraven.modules.docs.events]
    [tolgraven.modules.docs.subs]
    [tolgraven.modules.docs.views :as view]))

(def spec
  {:content [:docs]
   :server {:routes 'tolgraven.modules.docs.api/routes}
   :depends (get content-contract/module-dependencies :docs [])
   :id :docs
   :styles ["/css/tolgraven/modules/docs.min.css"]
   :pages pages/spec
   :view (merge {:page #'view/<page>} development/views)
   :init #(r/dispatch [:docs/init])})
