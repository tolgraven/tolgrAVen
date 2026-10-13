(ns website.modules.data-inspector.module
  (:require [website.modules.data-inspector.views :as views]))

(def spec
  {:id :data-inspector
   :view {:data #'views/<formatted-data>
          :error #'views/<error-full>}})
