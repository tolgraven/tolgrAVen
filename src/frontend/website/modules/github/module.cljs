(ns website.modules.github.module
  (:require
    [website.content.contract :as content-contract]
    [website.react :as r]
    [website.modules.github.events]
    [website.modules.github.subs]
    [website.modules.github.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :github [])
   :id :github
   :styles ["/css/tolgraven/modules/github.min.css"]
   :view {:view #'view/<commits>}
   :init #(r/dispatch [:github/init])})
