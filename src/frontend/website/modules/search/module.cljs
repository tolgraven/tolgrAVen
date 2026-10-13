(ns website.modules.search.module
  {:bundle/depends-on #{:main :link-preview :styled-input :user}}
  (:require
    [website.content.contract :as content-contract]
    [website.react :as r]
    [website.modules.search.events]
    [website.modules.search.subs]
    [website.modules.search.views :as view]))

(def spec
  {:server {:routes 'website.modules.search.api/routes
            :requires #{:blog}
            :sql ["supabase/modules/search.sql"]
            :sql-without-comments ["supabase/modules/search_posts.sql"]}
   :depends (get content-contract/module-dependencies :search [])
   :id :search
   :styles ["/css/website/modules/search.min.css"]
   :view {:view #'view/<ui>
          :button #'view/<button>
          :inline #'view/<inline>}
   :init #(r/dispatch [:search/init])})
