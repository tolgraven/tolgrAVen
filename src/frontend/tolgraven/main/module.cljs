(ns tolgraven.main.module
  (:require [tolgraven.main.pages :as pages]
            [tolgraven.content.contract :as content]
            [tolgraven.ssr.contract :as ssr]))

(def spec
  {:pages pages/spec
   :id :main
   :assets {}
   :preload-modules [:user :link-preview :search]
   :ssr ssr/landing-spec
   :depends (get content/module-dependencies :main)})
