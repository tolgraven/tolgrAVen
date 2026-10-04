(ns tolgraven.docs.module
  (:require
    [tolgraven.docs.pages :as pages]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.docs.events]
    [tolgraven.docs.subs]
    [tolgraven.docs.views :as view]))

(def spec
  {:content (get content-contract/module-content :docs [])
   :id :docs
   :pages pages/spec
   :view {:page #'view/page}
   :init #(rf/dispatch [:docs/init])})
