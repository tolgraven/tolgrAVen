(ns tolgraven.modules.codex-profile.module
  (:require [tolgraven.content.contract :as content]
            [tolgraven.modules.codex-profile.views :as views]))

(def spec
  {:id :codex-profile
   :content [:codex-profile]
   :styles ["/css/tolgraven/modules/codex-profile.min.css"]
   :depends (get content/module-dependencies :codex-profile [])
   :view {:view #'views/<profile>}})
