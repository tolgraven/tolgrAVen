(ns tolgraven.main.module
  (:require [tolgraven.content.contract :as content]))

(def spec
  {:id :main
   :assets {}
   :depends (get content/module-dependencies :main)})
