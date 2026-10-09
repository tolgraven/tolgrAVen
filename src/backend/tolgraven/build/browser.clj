(ns tolgraven.build.browser
  "Delegate to Shadow's browser target after discovering feature bundles."
  (:require [clojure.spec.alpha :as s]
            [tolgraven.build.modules :as modules]
            [shadow.build :as build]
            [shadow.build.config :as config]
            [shadow.build.targets.browser :as browser]))

(defmethod config/target-spec 'tolgraven.build.browser/process [_]
  (s/spec ::browser/target))

(defn process [{::build/keys [stage] :as state}]
  (browser/process
    (cond-> state
      (= :configure stage)
      (update-in [::build/config :modules]
                 #(merge (modules/bundles) %)))))

(defn test-modules
  {:shadow.build/stage :configure}
  [state]
  ;; The single browser-test bundle needs every discovered namespace available
  ;; to shadow.lazy, even when no test directly requires that module entry yet.
  (update-in state [::build/config :devtools :preloads]
             #(vec (distinct (concat % (map :entry (modules/discover)))))))
