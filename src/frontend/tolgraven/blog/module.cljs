(ns tolgraven.blog.module
  (:require [tolgraven.blog.pages :as pages]
            [tolgraven.blog.schema :as schema]

    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.blog.events]
    [tolgraven.blog.subs]
    [tolgraven.blog.views :as view]))

(def spec
  {:pages pages/spec
   :depends (get content-contract/module-dependencies :blog [])
   :id :blog
   :db-schema schema/sections
   :view {:page #'view/<blog-page>
          :post #'view/<blog-page>
          :archive #'view/<blog-page>
          :tag #'view/<blog-page>
          :new-post #'view/<blog-page>
          :posted-by #'view/<posted-by>
          :post-content #'view/<post-content>
          :tags-list #'view/<tags-list>
          :comments #'view/<comments-section>}
   :init #(rf/dispatch [:on-booted :store [:blog/init]])})
