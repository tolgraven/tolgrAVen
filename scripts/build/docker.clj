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
(def manifests ["Dockerfile.builder" "package.json" "package-lock.json"])
(def toolchain-manifests ["Dockerfile.toolchain"])
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

(defn input-hash [read-file paths]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [name paths]
      (.update digest (.getBytes (str name "\u0000") "UTF-8"))
      (.update digest (.getBytes (read-file name) "UTF-8")))
    (subs (format "%064x" (java.math.BigInteger. 1 (.digest digest))) 0 16)))

(defn dependency-hash [read-file] (input-hash read-file manifests))
(defn toolchain-hash [read-file] (input-hash read-file toolchain-manifests))

(defn toolchain-reference [hash]
  (str registry "/tolgraven/builder:toolchain-" hash))

(defn toolchain-tag []
  (str (toolchain-reference (toolchain-hash slurp)) "-" (last (str/split platform #"/"))))

(defn prefab-reference [hash]
  (str registry "/tolgraven/builder:deps-" hash))

(defn builder-tag []
  (str (prefab-reference (dependency-hash slurp)) "-" (last (str/split platform #"/"))))

(defn pinned-dockerfile [source image]
  (when-not (= 1 (count (re-seq #"(?m)^ARG BUILDER_IMAGE=.*$" source)))
    (throw (ex-info "Dockerfile must declare exactly one prefab default" {})))
  (str/replace source #"(?m)^ARG BUILDER_IMAGE=.*$" (str "ARG BUILDER_IMAGE=" image)))

(defn pinned-toolchain [source image]
  (when-not (= 1 (count (re-seq #"(?m)^ARG TOOLCHAIN_IMAGE=.*$" source)))
    (throw (ex-info "Dockerfile.builder must declare exactly one infrastructure default" {})))
  (str/replace source #"(?m)^ARG TOOLCHAIN_IMAGE=.*$" (str "ARG TOOLCHAIN_IMAGE=" image)))

(defn- pin-file! [path read-file staged? update-pin]
  (let [before (read-file path)
        after (update-pin before)]
    (when-not (= before after)
      (if staged?
        (let [blob (str/trim (:out (shell {:in after :out :string} ["git" "hash-object" "-w" "--stdin"])))]
          (shell ["git" "update-index" "--cacheinfo" (str "100644," blob "," path)])
          (spit path (update-pin (slurp path))))
        (spit path after)))))

(defn pin! [staged?]
  (let [read-file (if staged?
                    (fn [path] (:out (shell {:out :string :err :string} ["git" "show" (str ":" path)])))
                    slurp)
        toolchain (toolchain-reference (toolchain-hash read-file))]
    ;; Update the base pin before hashing the Node layer. Staged reads observe
    ;; the new index blob while working-tree edits outside both pins survive.
    (pin-file! "Dockerfile.builder" read-file staged? #(pinned-toolchain % toolchain))
    (let [image (prefab-reference (dependency-hash read-file))]
      (pin-file! "Dockerfile" read-file staged? #(pinned-dockerfile % image))
      image)))

(defn pin-staged! []
  (let [paths (set (str/split (:out (shell {:out :string} ["git" "diff" "--cached" "--name-only" "-z"])) #"\u0000"))]
    (when (some paths (concat manifests toolchain-manifests ["Dockerfile"])) (pin! true))))

(defn recipe-body [source]
  (let [start (re-find #"(?m)^(?:ARG INFRA_REVISION=|FROM )" source)]
    (when-not start (throw (ex-info "Docker recipe has no body" {})))
    (str/trim (subs source (.indexOf source start)))))

(defn check-prefab-recipe! []
  (let [source (slurp "Dockerfile")]
    (doseq [[label file] [["TOOLCHAIN" "Dockerfile.toolchain"] ["DEPENDENCY" "Dockerfile.builder"]]]
      (let [pattern (re-pattern (str "(?s)# BEGIN " label " FALLBACK\\n(.*?)# END " label " FALLBACK"))
            fragment (second (re-find pattern source))]
        (when-not (= (some-> fragment str/trim) (recipe-body (slurp file)))
          (throw (ex-info "Dockerfile fallback differs from its owned recipe" {:file file})))))))

(defn check-pin! []
  (check-prefab-recipe!)
  (let [toolchain (toolchain-reference (toolchain-hash slurp))
        image (prefab-reference (dependency-hash slurp))
        source (slurp "Dockerfile")]
    (when-not (= (slurp "Dockerfile.builder") (pinned-toolchain (slurp "Dockerfile.builder") toolchain))
      (throw (ex-info "Infrastructure changed: run bb docker pin and commit both Dockerfiles" {})))
    (when-not (= source (pinned-dockerfile source image))
      (throw (ex-info "Dependency inputs changed: run bb docker pin and commit Dockerfile" {})))
    image))

(defn prefab! [publish?]
  (check-pin!)
  (let [image (builder-tag)
        toolchain (toolchain-tag)
        present? (zero? (:exit (shell {:out :string :err :string :continue true}
                                              ["docker" "image" "inspect" image])))]
    (when-not present?
      (if (remote-digest image)
        (run! "docker" "pull" image)
        (do
          (when-not (zero? (:exit (shell {:out :string :err :string :continue true}
                                        ["docker" "image" "inspect" toolchain])))
            (if (remote-digest toolchain)
              (run! "docker" "pull" toolchain)
              (run! "docker" "build" "--platform" platform "--progress=plain"
                    "--build-arg" (str "BUILDPLATFORM=" platform)
                    "-f" "Dockerfile.toolchain" "-t" toolchain ".")))
          (run! "docker" "build" "--platform" platform "--progress=plain"
                "--build-arg" (str "TOOLCHAIN_IMAGE=" toolchain)
                "--build-arg" (str "BUILDPLATFORM=" platform)
                "-f" "Dockerfile.builder" "-t" image "."))))
    (when publish?
      (registry-check!)
      (when (zero? (:exit (shell {:out :string :err :string :continue true}
                                ["docker" "image" "inspect" toolchain])))
        (when-not (= (:Id (first (inspect toolchain))) (remote-digest toolchain))
          (run! "docker" "push" toolchain)))
      (let [id (:Id (first (inspect image)))]
        (when-not (= id (remote-digest image)) (run! "docker" "push" image)))
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
            protected-use (cond-> (vec in-use) (by-tag (toolchain-tag)) (conj (by-tag (toolchain-tag))))
            obsolete (cleanup-plan images protected-use (by-tag (builder-tag)) (by-tag current))]
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
                                               :toolchain (toolchain-reference (toolchain-hash slurp))
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
