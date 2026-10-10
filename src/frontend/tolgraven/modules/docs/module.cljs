(ns tolgraven.modules.docs.module
  (:require
    [tolgraven.modules.docs.pages :as pages]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.diagnostics.docs :as development]
    [tolgraven.modules.docs.events]
    [tolgraven.modules.docs.subs]
    [tolgraven.modules.docs.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :docs [])
   :id :docs
   :styles ["/css/tolgraven/modules/docs.min.css"]
   :pages pages/spec
   :view (merge {:page #'view/<page>} development/views)
   :init #(rf/dispatch [:docs/init])})
