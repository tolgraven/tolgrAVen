(ns website.modules.experiments.module
  {:bundle/depends-on #{:main :maps}}
  (:require [website.modules.experiments.views :as views]))

(def spec
  {:id :test
   :styles ["/css/website/modules/test.min.css"]
   :view {:page #'views/<test-page>}})
