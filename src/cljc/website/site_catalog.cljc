(ns website.site-catalog
  (:require #?(:clj [website.build.modules :refer [server-definitions]]))
  #?(:cljs (:require-macros [website.build.modules :refer [server-definitions]])))

(def modules (server-definitions))
