(ns website.modules.codex-profile.module
  (:require [website.modules.codex-profile.views :as views]))

(def spec
  ;; This section is not published by the CMS yet. Keep the optional view
  ;; available without adding an unsupported key to bootstrap/module requests.
  {:content []
   :view {:view #'views/<profile>}})
