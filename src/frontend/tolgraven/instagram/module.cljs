(ns tolgraven.instagram.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.instagram.events]
    [tolgraven.instagram.subs]
    [tolgraven.instagram.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :instagram [])
   :id :instagram
   :view {:view #'view/<instagram>}
   :init #(rf/dispatch [:instagram/init])})
