(ns tolgraven.loader.code
  "Platform-specific Shadow loadables; JVM tooling and SSR use eager specs."
  #?(:cljs (:require [shadow.lazy :as lazy]))
  #?(:cljs (:require-macros [tolgraven.build.modules :refer [loadables]])))

(def modules
  #?(:browser (loadables)
     :default {}))
