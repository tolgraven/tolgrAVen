(ns ^:dev/always website.loader.code
  "Platform-specific Shadow loadables; JVM tooling and SSR use eager specs."
  #?(:cljs (:require [shadow.lazy :as lazy]))
  #?(:cljs (:require-macros [website.build.modules :refer [loadables]])))

;; Discovery depends on the directory inventory, including newly added files.
;; Never reuse a cached macro expansion from an older inventory.
(def modules
  #?(:browser (loadables)
     :default {}))

(defn module-spec
  "Resolve the catalog-owned identity without changing an explicit declaration."
  [id spec]
  (if (and (map? spec) (not (contains? spec :id)))
    (assoc spec :id id)
    spec))
