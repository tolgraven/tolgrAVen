(ns ^:dev/always website.loader.code
  "Platform-specific Shadow loadables; JVM tooling and SSR use eager specs."
  #?(:cljs (:require [shadow.lazy :as lazy]))
  #?(:cljs (:require-macros [website.build.modules :refer [loadables]])))

;; Discovery depends on the directory inventory, including newly added files.
;; Never reuse a cached macro expansion from an older inventory.
(def modules
  #?(:browser (loadables)
     :default {}))
