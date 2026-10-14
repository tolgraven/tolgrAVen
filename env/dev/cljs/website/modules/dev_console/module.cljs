(ns website.modules.dev-console.module
  (:require [website.modules.dev-console.views :as views]))

(def spec
  {:id :dev-console
   :view {:console #'views/<console>}})
