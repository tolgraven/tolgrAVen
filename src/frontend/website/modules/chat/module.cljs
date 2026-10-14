(ns website.modules.chat.module
  (:require
    [website.modules.chat.storage :as storage]
    [website.modules.chat.events]
    [website.modules.chat.subs]
    [website.modules.chat.views :as view]))

(def spec
  {:bundle/depends-on #{:main :user}
   :server {:requires #{:markdown}
            :routes 'website.modules.chat.api/routes}
   :storage storage/tables
   :view {:view #'view/<chat>}})
