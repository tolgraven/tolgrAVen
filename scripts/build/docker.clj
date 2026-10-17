(ns build.docker
  "Local builds and publishing; host deployment policy lives under ops/host."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [util.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def registry (or (System/getenv "DOCKER_REGISTRY") "registry.bux.tolgraven.se"))
(def platform
  (or (System/getenv "DOCKER_PLATFORM")
      (case (System/getProperty "os.arch")
        "aarch64" "linux/arm64"
        ("amd64" "x86_64") "linux/amd64"
        (throw (ex-info "Set DOCKER_PLATFORM for this host architecture" {})))))
(def server-registry "127.0.0.1:5005")
(def manifests ["Dockerfile.builder" "project.clj" "package.json" "package-lock.json" "scripts/media/images.clj"])
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

(defn dependency-hash [read-file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [name manifests]
      (.update digest (.getBytes (str name "\u0000") "UTF-8"))
      (.update digest (.getBytes (read-file name) "UTF-8")))
    (subs (format "%064x" (java.math.BigInteger. 1 (.digest digest))) 0 16)))

(defn prefab-reference [hash]
  (str registry "/tolgraven/builder:deps-" hash))

(defn builder-tag []
  (str (prefab-reference (dependency-hash slurp)) "-" (last (str/split platform #"/"))))

(defn pinned-dockerfile [source image]
  (when-not (= 1 (count (re-seq #"(?m)^ARG BUILDER_IMAGE=.*$" source)))
    (throw (ex-info "Dockerfile must declare exactly one prefab default" {})))
  (str/replace source #"(?m)^ARG BUILDER_IMAGE=.*$" (str "ARG BUILDER_IMAGE=" image)))

(defn pin! [staged?]
  (let [read-file (if staged?
                    (fn [path] (:out (shell {:out :string :err :string} ["git" "show" (str ":" path)])))
                    slurp)
        image (prefab-reference (dependency-hash read-file))
        before (read-file "Dockerfile")
        after (pinned-dockerfile before image)]
    (when-not (= before after)
      (if staged?
        (let [blob (str/trim (:out (shell {:in after :out :string} ["git" "hash-object" "-w" "--stdin"])))]
          (shell ["git" "update-index" "--cacheinfo" (str "100644," blob ",Dockerfile")])
          ;; Preserve every unstaged edit; only the generated default is updated.
          (spit "Dockerfile" (pinned-dockerfile (slurp "Dockerfile") image)))
        (spit "Dockerfile" after))
      (println "Pinned Dockerfile prefab:" image))
    image))

(defn pin-staged! []
  (let [paths (set (str/split (:out (shell {:out :string} ["git" "diff" "--cached" "--name-only" "-z"])) #"\u0000"))]
    (when (some paths (conj manifests "Dockerfile")) (pin! true))))

(defn check-prefab-recipe! []
  (let [normalize (fn [source]
                    (-> source
                        (str/split #"ARG MAVEN_SEED_IMAGE=" 2) second
                        (str/split #"# Compile on the builder" 2) first
                        (str/replace #"--platform=\$(?:BUILD|TARGET)PLATFORM " "")
                        (str/replace #" AS prefab(?=\n)" "")
                        str/trim))]
    (when-not (= (normalize (slurp "Dockerfile.builder")) (normalize (slurp "Dockerfile")))
      (throw (ex-info "The Dockerfile fallback must match Dockerfile.builder" {})))))

(defn check-pin! []
  (check-prefab-recipe!)
  (let [image (prefab-reference (dependency-hash slurp))
        source (slurp "Dockerfile")]
    (when-not (= source (pinned-dockerfile source image))
      (throw (ex-info "Dependency inputs changed: run bb docker pin and commit Dockerfile" {})))
    image))

(defn prefab! [publish?]
  (let [image (builder-tag)
        present? (zero? (:exit (shell {:out :string :err :string :continue true}
                                              ["docker" "image" "inspect" image])))]
    (when-not present?
      (if (remote-digest image)
        (run! "docker" "pull" image)
        (let [seed (str registry "/tolgraven/builder:java21-node22-v2-" (last (str/split platform #"/")))
              seed' (if (remote-digest seed) seed "empty-maven-seed")]
          (run! "docker" "build" "--platform" platform "--progress=plain"
                "--build-arg" (str "MAVEN_SEED_IMAGE=" seed')
                "--build-arg" (str "BUILDPLATFORM=" platform)
                "-f" "Dockerfile.builder" "-t" image "."))))
    (when publish?
      (registry-check!)
      (let [id (:Id (first (inspect image)))
            aliases (map #(str registry "/tolgraven/builder:" %)
                         ["java21-node22-v1" (str "java21-node22-v2-" (last (str/split platform #"/")))])]
        (when-not (= id (remote-digest image)) (run! "docker" "push" image))
        (doseq [alias aliases]
          (when-not (= id (remote-digest alias))
            (run! "docker" "tag" image alias)
            (run! "docker" "push" alias))))
      (println "Published native prefab; the Coolify prefab gate publishes the shared multi-platform reference."))
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
  (when-not (#{"registry" "prefab" "build" "push" "deploy" "clean" "pin" "pin-staged" "check-pin" "metadata"} action)
    (throw (ex-info "Use bb docker <registry|prefab|build|push|deploy|clean|pin|check-pin|metadata>" {})))
  (when (= "deploy" action) (require-checkout!))
  (case action
    "pin" (pin! false)
    "pin-staged" (pin-staged!)
    "check-pin" (println (check-pin!))
    "metadata" (println (json/generate-string {:image (prefab-reference (dependency-hash slurp))
                                               :native-image (builder-tag)}))
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
