(ns tolgraven.modules.main.module
  (:require [tolgraven.content.contract :as content]
            [tolgraven.modules.main.layout :as layout]))

(def spec
  {:id :main
   :assets {}
   :icons ["brands/facebook" "brands/github" "brands/instagram"
           "brands/linkedin" "brands/soundcloud" "brands/twitter"]
   :ssr layout/landing-spec
   :depends (get content/module-dependencies :main)})
