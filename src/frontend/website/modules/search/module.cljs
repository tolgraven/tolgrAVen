(ns website.modules.search.module
  (:require
    [website.react :as r]
    [website.modules.search.storage :as storage]
    [website.modules.search.events]
    [website.modules.search.subs]
    [website.modules.search.views :as view]))

(def spec
  {:bundle/depends-on #{:main :styled-input}
   :server {:routes 'website.modules.search.api/routes
            :start 'website.modules.search.index/start!}
   :storage storage/tables
   :view {:view #'view/<ui>
          :button #'view/<button>
          :inline #'view/<inline>}
   :init #(r/dispatch [:search/init])})
