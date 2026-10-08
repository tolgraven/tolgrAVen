(ns build.docker
  "Local builds and publishing; host deployment policy lives under ops/host."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [util.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def registry (or (System/getenv "DOCKER_REGISTRY") "registry.bux.tolgraven.se"))
(def platform (or (System/getenv "DOCKER_PLATFORM") "linux/arm64"))
(def server-registry "127.0.0.1:5005")
(def manifests ["Dockerfile.builder" "project.clj" "package.json" "package-lock.json"])
(defn run! [& args] (shell (vec args)))
(defn output [& args] (str/trim (:out (shell {:out :string :err :string} (vec args)))))
(defn shell-quote [value] (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))
(defn inspect [image] (json/parse-string (output "docker" "image" "inspect" image) true))

(defn registry-check! []
  (let [{:keys [status headers]} (http/get (str "https://" registry "/v2/")
                                         {:throw false :timeout 15000})]
    (when-not (and (= 401 status) (str/starts-with? (get headers "www-authenticate" "") "Basic"))
      (throw (ex-info (str "Registry health check failed: HTTP " status) {})))
    (println "HTTPS registry ready:" registry "(authentication required)")))

(defn remote-digest [image]
  (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                            ["docker" "manifest" "inspect" image])]
    (cond
      (zero? exit) (get-in (json/parse-string out true) [:config :digest])
      (re-find #"(?i)manifest unknown|no such manifest" err) nil
      :else (throw (ex-info (str "Cannot inspect " image "; check docker login and registry connectivity. " err) {})))))

(defn builder-tag []
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [name manifests]
      (.update digest (.getBytes (str name "\u0000") "UTF-8"))
      (.update digest (java.nio.file.Files/readAllBytes (fs/path name))))
    (str registry "/tolgraven/builder:deps-" (subs (format "%064x" (java.math.BigInteger. 1 (.digest digest))) 0 16)
         "-" (last (str/split platform #"/")))))

(defn prefab! [publish?]
  (let [image (builder-tag)
        present? (zero? (:exit (shell {:out :string :err :string :continue true}
                                              ["docker" "image" "inspect" image])))]
    (when-not present?
      (if (remote-digest image)
        (run! "docker" "pull" image)
        (run! "docker" "build" "--platform" platform "--progress=plain"
              "-f" "Dockerfile.builder" "-t" image ".")))
    (when publish?
      (registry-check!)
      (let [id (:Id (first (inspect image)))
            alias (str registry "/tolgraven/builder:java21-node22-v1")]
        (when-not (= id (remote-digest image)) (run! "docker" "push" image))
        (when-not (= id (remote-digest alias))
          (run! "docker" "tag" image alias)
          (run! "docker" "push" alias))))
    image))

(defn image-kind [reference]
  (let [repository (str/replace reference #":[^/:]+$" "")]
    (or (some (fn [kind] (when (some #{repository} (map #(str % "/tolgraven/" kind) [registry server-registry])) kind))
              ["site" "strapi" "builder"])
        ({"tolgraven-builder" "builder" "tolgraven-fallback-check" "legacy"} repository))))

(defn cleanup-plan [images in-use prefab-id current-id]
  (let [newest (sort-by :Created #(compare %2 %1) images)
        protected (into (set (conj (vec in-use) prefab-id current-id))
                        (mapcat (fn [[kind n]]
                                  (take n (map :Id (filter #(some #{kind} (map image-kind (:RepoTags %))) newest)))))
                        [["site" 2] ["strapi" 1]])
        protected (cond-> protected
                    (nil? prefab-id) (into (map :Id (filter #(some #{"builder"} (map image-kind (:RepoTags %))) images))))]
    (vec (for [image images :when (not (protected (:Id image)))
               tag (:RepoTags image) :when (image-kind tag)] tag))))

(defn prune! [& [current]]
  (let [ids (distinct (str/split (output "docker" "image" "ls" "--quiet" "--no-trunc") #"\s+"))]
    (when (seq (remove str/blank? ids))
      (let [images (json/parse-string (apply output "docker" "image" "inspect" ids) true)
            containers (remove str/blank? (str/split (output "docker" "ps" "-aq") #"\s+"))
            in-use (when (seq containers) (str/split (apply output "docker" "inspect" "--format" "{{.Image}}" containers) #"\s+"))
            current (or current (when (fs/exists? ".local-wip/docker/last-image") (str/trim (slurp ".local-wip/docker/last-image"))))
            by-tag (into {} (for [image images, tag (:RepoTags image)] [tag (:Id image)]))
            obsolete (cleanup-plan images in-use (by-tag (builder-tag)) (by-tag current))]
        (if (seq obsolete)
          ;; No --force: Docker protects containers created after this snapshot.
          (shell {:continue true} (into ["docker" "image" "rm"] obsolete))
          (println "No obsolete local tolgraven image tags."))))))

(defn build! []
  (let [builder (prefab! false)
        revision (output "git" "rev-parse" "HEAD")
        dirty? (not (str/blank? (output "git" "status" "--porcelain")))
        stamp (.format (.withZone (DateTimeFormatter/ofPattern "yyyyMMddHHmmss") ZoneOffset/UTC) (Instant/now))
        image (str registry "/tolgraven/site:" (subs revision 0 12) (when dirty? "-dirty") "-" stamp)]
    (run! "docker" "build" "--platform" platform "--progress=plain"
          "--build-arg" (str "BUILDER_IMAGE=" builder) "--build-arg" (str "VCS_REF=" revision) "-t" image ".")
    (fs/create-dirs ".local-wip/docker")
    (spit ".local-wip/docker/last-image" (str image "\n"))
    (println "Built" image)
    image))

(defn require-checkout! []
  (let [origin (-> (output "git" "remote" "get-url" "origin")
                   str/lower-case (str/replace #"\.git$|/$" "")
                   (str/replace "git@github.com:" "https://github.com/"))]
    (when-not (= "https://github.com/tolgraven/tolgraven" origin)
      (throw (ex-info "Docker deploy is scoped to tolgraven staging; use the site provisioner for another site" {})))))

(defn -main [& [action]]
  (when-not (#{"registry" "prefab" "build" "push" "deploy" "clean"} action)
    (throw (ex-info "Use bb docker <registry|prefab|build|push|deploy|clean>" {})))
  (when (= "deploy" action) (require-checkout!))
  (case action
    "registry" (registry-check!)
    "clean" (do (prune!) (run! "docker" "image" "prune" "--force")
                (run! "docker" "buildx" "prune" "--force" "--max-used-space" "4GB"))
    "prefab" (do (println (prefab! true)) (prune!))
    (let [image (build!)]
      (when (#{"push" "deploy"} action) (registry-check!) (run! "docker" "push" image))
      (prune! image)
      (when (= "deploy" action)
        (let [server-image (str server-registry "/tolgraven/site:" (last (str/split image #":")))
              command (str/join " " (map shell-quote ["python3" "/usr/local/lib/tolgraven/deploy-image.py"
                                                          server-image "--pr" (or (System/getenv "COOLIFY_PR") "0")]))]
          (shell {:timeout 1000000}
                         ["ssh" "-o" "BatchMode=yes" "-o" "ConnectTimeout=10"
                          (or (System/getenv "COOLIFY_SSH_HOST") "bux") command]))))))
