(ns dev.tasks
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [util.process :refer [shell]]
            [clojure.string :as str]))

(defn audit! [& [label]]
  (let [label (or label "current")]
    (when-not (re-matches #"[A-Za-z0-9_-]+" label)
      (throw (ex-info "Use an alphanumeric audit label (hyphens/underscores allowed)" {})))
    (shell ["lein" "with-profile" "prod,provided" "run" "-m" "tolgraven.build.audit" label])
    (shell ["node" "scripts/build/bundle-sizes.mjs" (str "target/bundle-audit/" label)])))

(def pair-operations
  #{"discover-app" "app-summary" "eval-cljs" "console-tail" "handler-source"
    "inject-runtime" "dispatch" "trace-recent" "watch-epochs" "tail-build"})

(defn pair! [& [operation & args]]
  (let [operation (or operation "discover-app")
        directory (or (System/getenv "REFRAME_PAIR_SKILL_DIR")
                      (str (or (System/getenv "CODEX_HOME") (fs/path (fs/home) ".codex")) "/skills/re-frame-pair"))
        script (fs/path directory "scripts" (str operation ".sh"))]
    (when-not (and (pair-operations operation) (fs/regular-file? script))
      (throw (ex-info "Unknown operation or missing re-frame-pair installation; see doc/re-frame-pair.md" {})))
    (shell {:extra-env
                    (cond-> {"SHADOW_CLJS_BUILD_ID" (or (System/getenv "SHADOW_CLJS_BUILD_ID") "app-dev")}
                      (and (nil? (System/getenv "SHADOW_CLJS_NREPL_PORT")) (fs/exists? ".nrepl-port"))
                      (assoc "SHADOW_CLJS_NREPL_PORT" (str/trim (slurp ".nrepl-port"))))}
                   (into ["bash" (str script)] args))))

(defn integration! [& args]
  (shell (into ["python3" "scripts/test/integration_server.py"] args)))

(defn test! []
  (shell ["bb" "--classpath" "scripts:test/scripts" "-m" "tools-test"])
  (shell ["python3" "-m" "unittest" "discover" "-s" "test/scripts" "-p" "*_test.py"]))

(defn supabase! [& [action & args]]
  (when-not (#{"schema" "import" "all"} action)
    (throw (ex-info "Use bb supabase <schema|import|all> [firebase-export.json]" {})))
  (when (and (#{"import" "all"} action) (not= 1 (count args)))
    (throw (ex-info "Import requires one Firebase export path" {})))
  (doseq [command (if (= action "all") ["schema" "import"] [action])]
    (shell (into ["lein" "run" "-m" "tolgraven.provision.supabase.cli" command]
                         (when (= command "import") args)))))
