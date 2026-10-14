(ns website.deployment
  "Public identity of this running container; independent of the hosting provider."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [website.config :as config]
            [website.deployment.schema :as schema])
  (:import [java.time Instant]))

(defn public-info [metadata repository]
  (let [commit (when (and (string? (:commit metadata))
                          (re-matches #"[a-fA-F0-9]{40}" (:commit metadata))) (:commit metadata))
        deployed-at (try (some-> (:deployed-at metadata) Instant/parse str)
                         (catch Exception _ nil))
        repository' (when (and (string? repository)
                               (re-matches #"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+" repository)) repository)]
    {:commit commit
     :deployed-at deployed-at
     :commit-url (when (and commit repository')
                   (str "https://github.com/" repository' "/commit/" commit))}))

(defn info []
  (let [file (io/file (or (:deploy-file config/env) "/app/deployment.json"))
        baked (when (.isFile file)
                (try (json/read-str (slurp file) :key-fn keyword) (catch Exception _ nil)))
        metadata (merge baked (into {} (remove (comp nil? val))
                                    {:commit (:deploy-commit config/env)
                                     :deployed-at (:deployed-at config/env)}))]
    (public-info metadata (or (:deploy-repository config/env) (:repository baked)))))

(defn response [_]
  {:status 200
   :headers {"Cache-Control" "no-store"}
   :body (info)})
