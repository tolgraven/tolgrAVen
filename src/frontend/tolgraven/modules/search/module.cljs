(ns tolgraven.modules.search.module
  {:bundle/depends-on #{:main :link-preview :styled-input :user}}
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.modules.search.events]
    [tolgraven.modules.search.subs]
    [tolgraven.modules.search.views :as view]))

(def spec
  {:server {:routes 'tolgraven.modules.search.api/routes
            :requires #{:blog}
            :sql ["supabase/modules/search.sql"]
            :sql-without-comments ["supabase/modules/search_posts.sql"]}
   :depends (get content-contract/module-dependencies :search [])
   :id :search
   :styles ["/css/tolgraven/modules/search.min.css"]
   :view {:view #'view/<ui>
          :button #'view/<button>}
   :init #(rf/dispatch [:search/init])})
