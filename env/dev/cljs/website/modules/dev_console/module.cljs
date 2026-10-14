(ns website.modules.dev-console.module
  (:require [website.modules.dev-console.views :as views]))

(def spec
  {:view {:console #'views/<console>}})
