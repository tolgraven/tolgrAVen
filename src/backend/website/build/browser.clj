(ns website.build.browser
  "Delegate to Shadow's browser target after discovering feature bundles."
  (:require [clojure.spec.alpha :as s]
            [website.build.modules :as modules]
            [shadow.build :as build]
            [shadow.build.config :as config]
            [shadow.build.test-util :as test-util]
            [shadow.build.targets.browser :as browser]))

(defmethod config/target-spec 'website.build.browser/process [_]
  (s/spec ::browser/target))

(defn process [{::build/keys [stage] :as state}]
  (browser/process
    (cond-> state
      (= :configure stage)
      (update-in [::build/config :modules]
                 #(merge (modules/bundles (modules/development-build? state)) %))
      (= :configure stage)
      (assoc-in [:js-options :export-conditions]
                ["worker" "browser" "require" "default"]))))

(defn test-modules
  {:shadow.build/stage :configure}
  [state]
  ;; The single browser-test bundle needs every discovered namespace available
  ;; to shadow.lazy, even when no test directly requires that module entry yet.
  (let [entries (mapv :entry (modules/discover modules/root (modules/development-build? state)))]
    (if (= :release (::build/mode state))
      ;; Shadow's browser-test target excludes development preloads in release.
      ;; Its native namespace list supplies the same selected module entries to
      ;; the single test bundle so shadow.lazy can resolve their real owners.
      (-> state
          (assoc-in [::build/config :namespaces]
                    (vec (distinct (concat (test-util/find-test-namespaces state (::build/config state)) entries))))
          ;; Config merges union sets, so adding #{:browser} cannot remove :dev.
          ;; A release fixture must exercise the deferred validation engine.
          (update-in [::build/config :compiler-options :reader-features] disj :dev)
          (update-in [:compiler-options :reader-features] disj :dev))
      (update-in state [::build/config :devtools :preloads]
                 #(vec (distinct (concat % entries)))))))
