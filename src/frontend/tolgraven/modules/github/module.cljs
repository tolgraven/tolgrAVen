(ns tolgraven.modules.github.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.modules.github.events]
    [tolgraven.modules.github.diff-events]
    [tolgraven.modules.github.pages :as pages]
    [tolgraven.modules.github.diff-views :as diff-views]
    [tolgraven.modules.github.subs]
    [tolgraven.modules.github.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :github [])
   :id :github
   :pages pages/spec
   :styles ["/css/tolgraven/modules/github.min.css"]
   :view {:view #'view/<commits>
          :diff #'diff-views/<page>}
   :init #(rf/dispatch [:github/init])})
