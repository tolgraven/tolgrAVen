(ns website.modules.blog.comment-exports
  "Selected comment views share one optional import and export boundary."
  #?(:feature/blog-comments
     (:require [website.modules.blog.comment-views :as views])))

(def views
  #?(:feature/blog-comments {:comment-quote #'views/<comment-quote>
                            :comments #'views/<comments-section>}
     :default {}))
