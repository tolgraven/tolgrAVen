(ns tolgraven.modules.github.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.modules.github.events]
    [tolgraven.modules.github.subs]
    [tolgraven.modules.github.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :github [])
   :id :github
   :styles ["/css/tolgraven/modules/github.min.css"]
   :view {:view #'view/<commits>}
   :init #(rf/dispatch [:github/init])})
