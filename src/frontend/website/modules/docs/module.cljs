(ns website.modules.docs.module
  (:require
    [website.modules.docs.pages :as pages]
    [website.react :as r]
    [website.diagnostics.docs :as development]
    [website.modules.docs.events]
    [website.modules.docs.subs]
    [website.modules.docs.views :as view]))

(def spec
  {:content [:docs]
   :server {:routes 'website.modules.docs.api/routes}
   :pages pages/spec
   :view (merge {:page #'view/<page>} development/views)
   :init #(r/dispatch [:docs/init])})
