(ns website.modules.gpt.module
  (:require
    [website.modules.gpt.storage :as storage]
    [website.modules.gpt.events]
    [website.modules.gpt.subs]
    [website.modules.gpt.views :as view]))

(def spec
  {:bundle/depends-on #{:main :user}
   :server {:routes 'website.modules.gpt.api/routes}
   :storage storage/tables
   :view {:view #'view/<threads>}})
