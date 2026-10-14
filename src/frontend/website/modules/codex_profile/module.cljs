(ns website.modules.codex-profile.module
  (:require [website.modules.codex-profile.views :as views]))

(def spec
  {:content [:codex-profile]
   :view {:view #'views/<profile>}})
