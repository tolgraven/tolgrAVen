(ns website.modules.codex-profile.module
  (:require [website.content.contract :as content]
            [website.modules.codex-profile.views :as views]))

(def spec
  {:id :codex-profile
   :content [:codex-profile]
   :styles ["/css/website/modules/codex-profile.min.css"]
   :depends (get content/module-dependencies :codex-profile [])
   :view {:view #'views/<profile>}})
