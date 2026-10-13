(ns website.modules.blog.module
  {:bundle/depends-on #{:main :user :link-preview}}
  (:require-macros [website.build.site :refer [when-enabled]])
  (:require [website.modules.blog.storage :as storage]
            [website.modules.blog.pages :as pages]
            [website.modules.blog.schema :as schema]

    [website.content.contract :as content-contract]
    [website.react :as r]
    [website.modules.blog.install :as install]
    [website.modules.blog.events]
    [website.modules.blog.subs]
    [website.modules.blog.views :as view]))

(def spec
  {:content [:blog :common]
   :server {:routes 'website.modules.blog.api/routes}
   :storage storage/tables
   :pages pages/spec
   :depends (get content-contract/module-dependencies :blog [])
   :id :blog
   :styles ["/css/tolgraven/modules/blog.min.css"]
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
