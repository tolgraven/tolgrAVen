(ns tolgraven.main.module
  (:require [tolgraven.content.contract :as content]
            [tolgraven.ssr.contract :as ssr]))

(def spec
  {:id :main
   :assets {}
   :ssr ssr/landing-spec
   :depends (get content/module-dependencies :main)})
