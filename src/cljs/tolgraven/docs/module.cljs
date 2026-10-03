(ns tolgraven.docs.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [re-frame.core :as rf]
    [tolgraven.docs.events]
    [tolgraven.docs.subs]
    [tolgraven.docs.views :as view]))

(def spec
  {:content (get content-contract/module-content :docs [])
   :id :docs
   :view {:page #'view/page}
   :init #(rf/dispatch [:docs/init])})
