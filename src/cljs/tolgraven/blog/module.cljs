(ns tolgraven.blog.module
  (:require
    [tolgraven.blog.events]
    [tolgraven.blog.subs]
    [tolgraven.blog.views :as view]))

;; Ideas: if first event/sub(?) not registered, auto load module?

(def spec
  {:id :blog
   :view {:page #'view/blog-page
          :post #'view/blog-post-page
          :archive #'view/blog-archive-page
          :tag #'view/blog-tag-page
          :new-post #'view/post-blog-page
          :posted-by #'view/posted-by
          :tags-list #'view/tags-list}})
