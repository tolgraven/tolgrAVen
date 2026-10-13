(ns tolgraven.modules.main.module
  (:require [tolgraven.modules.main.storage :as storage]
            [tolgraven.content.contract :as content]
            [tolgraven.modules.main.layout :as layout]))

(def spec
  {:content [:document :header :common :footer :post-footer]
   :server {:routes 'tolgraven.modules.main.api/routes}
   :storage storage/tables
   :id :main
   :assets {}
   :icons ["brands/facebook" "brands/github" "brands/instagram"
           "brands/linkedin" "brands/soundcloud" "brands/twitter"]
   :ssr layout/landing-spec
   :depends (get content/module-dependencies :main)})
