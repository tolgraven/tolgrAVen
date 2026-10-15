(ns dev.pr
  "Repository-scoped gh pr wrapper and commit session attribution."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def repository "tolgraven/tolgrAVen")
(def start-marker "<!-- codex-sessions:start -->")
(def end-marker "<!-- codex-sessions:end -->")

(defn session-id [value]
  (when-not (and (string? value)
                 (re-matches #"(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}" value))
    (throw (ex-info "Session must be a Codex thread UUID" {})))
  (str/lower-case value))

(defn current-session []
  (some-> (first (remove str/blank? [(System/getenv "CODEX_THREAD_ID")
                                   (System/getenv "CODEX_SESSION_ID")])) session-id))

(defn run! [args & [options]]
  (process/check @(process/process args (merge {:out :string :err :inherit} options))))

(defn output [args] (str/trim (:out (run! args))))

(defn split-footer [body]
  (let [body (or body "")
        starts (count (re-seq (re-pattern (java.util.regex.Pattern/quote start-marker)) body))
        ends (count (re-seq (re-pattern (java.util.regex.Pattern/quote end-marker)) body))]
    (if (and (zero? starts) (zero? ends))
      {:body body}
      (let [pattern (re-pattern (str "(?s)" (java.util.regex.Pattern/quote start-marker)
                                     "\\n<!-- (.*?) -->\\n.*?"
                                     (java.util.regex.Pattern/quote end-marker)))
            match (re-find pattern body)]
        (when-not (and (= 1 starts ends) match)
          (throw (ex-info "Malformed or duplicate Codex session footer; preserve and repair it before editing" {})))
        (let [{:keys [owner contributors]} (json/parse-string (second match) true)]
          (when-not (vector? contributors)
            (throw (ex-info "Malformed Codex session contributors" {})))
          {:body (str/replace body (first match) "")
           :owner (session-id owner)
           :contributors (mapv session-id contributors)})))))

(defn append-footer [body previous session contributors]
  (let [supplied (split-footer body)
        old (split-footer previous)
        owner (or (:owner old) (:owner supplied) session)
        contributors' (vec (remove #{owner} (distinct (concat (:contributors old)
                                                               (:contributors supplied)
                                                               [session] contributors))))
        metadata {:owner owner, :contributors contributors'}
        entry (fn [role id] (str "- " role ": `codex resume " id "`\n"))]
    (str (str/trimr (:body supplied)) "\n\n" start-marker "\n<!-- "
         (json/generate-string metadata) " -->\n"
         "### Codex sessions\n\n"
         (entry "In charge" owner)
         (if (seq contributors')
           (apply str (map #(entry "Contributor" %) contributors'))
           "- Other contributing sessions: none recorded.\n")
         end-marker "\n")))

(def value-flags
  #{"--body" "-b" "--body-file" "-F" "--title" "-t" "--base" "-B" "--head" "-H"
    "--repo" "-R" "--assignee" "-a" "--label" "-l" "--reviewer" "-r"
    "--project" "-p" "--milestone" "-m" "--add-assignee" "--remove-assignee"
    "--add-label" "--remove-label" "--add-reviewer" "--remove-reviewer"
    "--add-project" "--remove-project" "--session" "--contributor"})
(def switch-flags #{"--draft" "-d" "--no-maintainer-edit" "--remove-milestone"})

(defn parse-args [args]
  (loop [[arg & more] args result {:forward [], :contributors [], :selectors []}]
    (if-not arg
      result
      (let [[flag inline] (or (when (str/starts-with? arg "--") (str/split arg #"=" 2))
                              (when (and (str/starts-with? arg "-") (> (count arg) 2))
                                [(subs arg 0 2) (subs arg 2)])
                              [arg nil])]
        (cond
          (value-flags flag)
          (let [value (or inline (first more))
                remaining (if inline more (next more))]
            (when (nil? value) (throw (ex-info (str flag " requires a value") {})))
            (recur remaining
                   (cond
                     (= flag "--session") (assoc result :session (session-id value))
                     (= flag "--contributor") (update result :contributors conj (session-id value))
                     (#{"--body" "-b" "--body-file" "-F"} flag)
                     (do (when (contains? result :body-source)
                           (throw (ex-info "Supply exactly one body or body-file" {})))
                         (assoc result :body-source [(if (#{"--body" "-b"} flag) :text :file) value]))
                     (#{"--repo" "-R"} flag)
                     (do (when-not (= repository value)
                           (throw (ex-info "This wrapper is scoped to tolgraven/tolgrAVen" {}))) result)
                     :else (-> result (update :forward into [flag value])
                               (assoc-in [:values flag] value)))))
          (and (switch-flags flag) (nil? inline))
          (recur more (update result :forward conj arg))
          (str/starts-with? arg "-")
          (throw (ex-info (str "Unsupported write flag " arg "; use --body/--body-file and see doc/tooling.md") {}))
          :else (recur more (update result :selectors conj arg)))))))

(defn trailer-sessions [messages]
  (vec (distinct (map (comp session-id second)
                     (mapcat #(re-seq #"(?im)^Codex-Session: ([0-9a-f-]+)\s*$" %) messages)))))

(defn body-text [[kind value]]
  (if (= kind :text) value (slurp (if (= "-" value) *in* value))))

(defn write-pr! [operation args]
  (let [{:keys [forward selectors values body-source contributors session]} (parse-args args)
        session' (or session (current-session)
                     (throw (ex-info "Set CODEX_THREAD_ID or supply --session UUID" {})))
        _ (when (or (> (count selectors) 1) (and (= operation "create") (seq selectors)))
            (throw (ex-info "create takes no selector; edit takes at most one PR selector" {})))
        _ (when (and (= operation "create") (nil? body-source))
            (throw (ex-info "create requires --body or --body-file (use - for stdin)" {})))
        existing (when (= operation "edit")
                   (json/parse-string
                     (output (into ["gh" "pr" "view" "--repo" repository]
                                   (concat selectors ["--json" "body,commits,url"]))) true))
        branch (when (= operation "create") (output ["git" "branch" "--show-current"]))
        head (or (get values "--head") (get values "-H") branch)
        _ (when (and (= operation "create") (or (str/blank? branch) (not= branch head)))
            (throw (ex-info "Create from the current feature branch; --head must match it" {})))
        base (or (get values "--base") (get values "-B") "master")
        messages (if existing
                   (map :messageBody (:commits existing))
                   [(output ["git" "log" (str "origin/" base "..HEAD")
                             "--format=%(trailers:key=Codex-Session)"])])
        body (append-footer (if body-source (body-text body-source) (:body existing))
                            (:body existing) session' (concat contributors (trailer-sessions messages)))
        command (into ["gh" "pr" operation "--repo" repository]
                      (concat (if existing [(:url existing)] []) forward
                              (when (and (= operation "create") (not (or (values "--base") (values "-B"))))
                                ["--base" "master"])
                              ["--body-file" "-"]))]
    (run! command {:in body, :out :inherit})))

(defn commit-session! [path]
  (when-let [session (current-session)]
    (let [body (slurp path)
          sessions (trailer-sessions [body])]
      (when-not (some #{session} sessions)
        (run! ["git" "interpret-trailers" "--in-place" "--trailer" (str "Codex-Session: " session) path])))))

(def help-text
  "Usage: bb pr create --title TITLE --body-file FILE [gh flags]\n       bb pr edit [PR] [--body-file FILE] [gh flags]\n       bb pr <view|checks|diff|ready|merge|...> [gh flags]\nWrite options: --session UUID, repeatable --contributor UUID. See doc/tooling.md.")

(defn -main [& [operation & args]]
  (try
    (cond
      (= operation "commit-session") (commit-session! (first args))
      (or (nil? operation) (#{"--help" "-h"} operation)) (println help-text)
      (and (#{"create" "edit"} operation) (not (#{["--help"] ["-h"]} (vec args))))
      (write-pr! operation args)
      :else (run! (into ["gh" "pr" operation "--repo" repository] args)
                  {:out :inherit, :in :inherit}))
    (catch Exception error
      (binding [*out* *err*] (println (ex-message error)))
      (System/exit (or (:exit (ex-data error)) 1)))))
