(ns tolgraven.site-catalog
  (:require #?(:clj [tolgraven.build.modules :refer [server-definitions]]))
  #?(:cljs (:require-macros [tolgraven.build.modules :refer [server-definitions]])))

(def modules (server-definitions))
