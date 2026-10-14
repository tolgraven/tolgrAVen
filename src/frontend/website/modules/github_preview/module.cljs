(ns website.modules.github-preview.module
  (:require [website.modules.github-preview.views :as views]))

(def spec
  {:site/owner :github
   :site/requires #{:search}
   :bundle/depends-on #{:search}
   :styles ["/css/website/modules/github.min.css"]
   :view {:commit #'views/<commit>}})
