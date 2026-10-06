(ns tolgraven.github.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.github.events]
    [tolgraven.github.subs]
    [tolgraven.github.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :github [])
   :id :github
   :view {:view #'view/<commits>}
   :init #(rf/dispatch [:github/init])})
