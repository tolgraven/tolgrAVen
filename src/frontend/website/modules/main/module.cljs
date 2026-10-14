(ns website.modules.main.module
  (:require [website.modules.main.storage :as storage]
            [website.content.contract :as content]
            [website.modules.main.layout :as layout]))

(def spec
  {:content [:document :header :common :footer :post-footer]
   :server {:routes 'website.modules.main.api/routes}
   :storage storage/tables
   :id :main
   :assets {}
   :icons ["brands/facebook" "brands/github" "brands/instagram"
           "brands/linkedin" "brands/soundcloud" "brands/twitter"]
   :ssr layout/landing-spec
   :depends (get content/module-dependencies :main)})
