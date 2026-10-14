(ns website.modules.blog.module
  (:require-macros [website.build.site :refer [when-enabled]])
  (:require [website.modules.blog.storage :as storage]
            [website.modules.blog.pages :as pages]
            [website.modules.blog.schema :as schema]

    [website.react :as r]
    [website.modules.blog.install :as install]
    [website.modules.blog.events]
    [website.modules.blog.subs]
    [website.modules.blog.views :as view]))

(def spec
  {:bundle/depends-on #{:main :user}
   :content [:blog :common]
   :styles ["/css/website/modules/blog.min.css"
            "/css/website/modules/monospace.min.css"
            "/css/website/modules/markdown.min.css"]
   :server {:requires #{:markdown}
            :routes 'website.modules.blog.api/routes}
   :storage storage/tables
   :pages pages/spec
   :install install/cache!
   :db-schema schema/sections
   :view {:page #'view/<blog-page>
          :post #'view/<blog-page>
          :archive #'view/<blog-page>
          :tag #'view/<blog-page>
          :posted-by #'view/<posted-by>
          :post-content #'view/<post-content>
          :tags-list #'view/<tags-list>
          :comment-quote (when-enabled :blog :comments #'view/<comment-quote>)
          :comments (when-enabled :blog :comments #'view/<comments-section>)}
   :init #(r/dispatch [:on-booted :store [:blog/init]])})
