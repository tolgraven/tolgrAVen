(ns tolgraven.docs.module
  (:require
    [tolgraven.docs.pages :as pages]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.docs.events]
    [tolgraven.docs.subs]
    [tolgraven.docs.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :docs [])
   :id :docs
   :pages pages/spec
   :view {:page #'view/<page>}
   :init #(rf/dispatch [:docs/init])})
