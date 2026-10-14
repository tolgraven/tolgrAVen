(ns website.modules.blog.module
  (:require-macros [website.build.site :refer [when-enabled]])
  (:require [website.modules.blog.storage :as storage]
            [website.modules.blog.pages :as pages]
            [website.modules.blog.schema :as schema]
            [website.modules.blog.comment-exports :as comments]

    [website.react :as r]
    [website.modules.blog.install :as install]
    [website.modules.blog.events]
    [website.modules.blog.subs]
    [website.modules.blog.views :as view]
    [website.modules.blog.presentation :as presentation]))

(def spec
  {:bundle/depends-on #{:main :user}
   :content [:blog :common]
   :styles ["/css/website/modules/blog.min.css"
            "/css/website/modules/monospace.min.css"
            "/css/website/modules/markdown.min.css"]
   :server {:requires #{:markdown}
            :routes 'website.modules.blog.api/routes
            :search [{:kind "blog-post"
                      :preview :blog-preview/post
                      :sql "supabase/modules/search_blog_posts.sql"}
                     {:kind "blog-comment"
                      :feature :comments
                      :preview :blog-preview/comment
                      :sql "supabase/modules/search_blog_comments.sql"}]}
   :storage storage/tables
   :pages pages/spec
   :install install/cache!
   :db-schema schema/sections
   :view (merge {:page #'view/<blog-page>
                 :post #'view/<blog-page>
                 :comment (when-enabled :blog :comments #'view/<blog-page>)
                 :archive #'view/<blog-page>
                 :tag #'view/<blog-page>
                 :posted-by #'presentation/<posted-by>
                 :post-content #'view/<post-content>
                 :tags-list #'view/<tags-list>}
                comments/views)
   :init #(r/dispatch [:on-booted :store [:blog/init]])})
