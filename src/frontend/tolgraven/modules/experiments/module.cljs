(ns tolgraven.modules.experiments.module
  {:bundle/depends-on #{:main :maps}}
  (:require [tolgraven.modules.experiments.views :as views]))

(def spec
  {:id :test
   :styles ["/css/tolgraven/modules/test.min.css"]
   :view {:page #'views/<test-page>}})
