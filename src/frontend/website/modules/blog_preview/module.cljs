(ns website.modules.blog-preview.module
  (:require [website.modules.blog-preview.views :as views])
  (:require-macros [website.build.site :refer [when-enabled]]))

(def spec
  {:site/owner :blog
   :site/requires #{:search}
   :bundle/depends-on #{:search}
   :styles ["/css/website/modules/blog.min.css"]
   :view (merge {:post #'views/<post>}
                (when-enabled :blog :comments {:comment #'views/<comment>}))})
