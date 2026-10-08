(ns tolgraven.modules.experiments.module
  {:bundle/depends-on #{:main :maps}}
  (:require [tolgraven.modules.experiments.views :as views]))

(def spec
  {:id :test
   :assets {:css ["https://unpkg.com/leaflet@1.7.1/dist/leaflet.css"]}
   :view {:page #'views/<test-page>}})
