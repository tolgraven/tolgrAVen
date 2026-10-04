(ns tolgraven.search.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.search.events]
    [tolgraven.search.subs]
    [tolgraven.search.views :as view]))

(def spec
  {:content (get content-contract/module-content :search [])
   :id :search
   :view {:view #'view/ui
          :button #'view/button}
   :init #(rf/dispatch [:search/init])})
