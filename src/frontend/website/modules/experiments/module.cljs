(ns website.modules.experiments.module
  (:require [website.modules.experiments.views :as views]))

(def spec
  {:bundle/depends-on #{:main :maps}
   :id :test
   :styles ["/css/website/modules/test.min.css"]
   :view {:page #'views/<test-page>}})
