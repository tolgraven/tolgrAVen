(ns website.modules.search.module
  (:require
    [website.react :as r]
    [website.modules.search.events]
    [website.modules.search.subs]
    [website.modules.search.views :as view]))

(def spec
  {:bundle/depends-on #{:main :styled-input :user}
   :server {:routes 'website.modules.search.api/routes
            :requires #{:blog :markdown}
            :sql ["supabase/modules/search.sql"]
            :sql-without-comments ["supabase/modules/search_posts.sql"]}
   :view {:view #'view/<ui>
          :button #'view/<button>
          :inline #'view/<inline>}
   :init #(r/dispatch [:search/init])})
