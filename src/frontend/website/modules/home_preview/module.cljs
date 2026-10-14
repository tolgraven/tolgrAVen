(ns website.modules.home-preview.module
  (:require [website.modules.search.presentation :as presentation]))

(def spec
  {:site/owner :home
   :site/requires #{:search}
   :bundle/depends-on #{:search}
   :styles ["/css/website/modules/search.min.css"]
   :view {:section #'presentation/<fallback>}})
