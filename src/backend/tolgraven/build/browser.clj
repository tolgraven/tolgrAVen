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
