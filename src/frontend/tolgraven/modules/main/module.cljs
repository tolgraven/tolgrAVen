(ns tolgraven.modules.main.module
  (:require [tolgraven.modules.main.pages :as pages]
            [tolgraven.content.contract :as content]
            [tolgraven.modules.main.layout :as layout]))

(def spec
  {:pages pages/spec
   :id :main
   :assets {}
   :ssr layout/landing-spec
   :depends (get content/module-dependencies :main)})
