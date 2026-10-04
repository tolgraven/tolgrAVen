(ns tolgraven.blog.module
  (:require [tolgraven.blog.pages :as pages]

    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.blog.events]
    [tolgraven.blog.subs]
    [tolgraven.blog.views :as view]))

(def spec
  {:pages pages/spec
   :content (get content-contract/module-content :blog [])
   :depends (get content-contract/module-dependencies :blog [])
   :id :blog
   :view {:page #'view/<blog-page>
          :post #'view/<blog-post-page>
          :archive #'view/<blog-archive-page>
          :tag #'view/<blog-tag-page>
          :new-post #'view/<post-blog-page>
          :posted-by #'view/<posted-by>
          :tags-list #'view/<tags-list>
          :comments #'view/<comments-section>}
   :init #(rf/dispatch [:on-booted :store [:blog/init]])})
