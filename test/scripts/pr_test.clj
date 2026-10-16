(ns pr-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [dev.pr :as pr]))

(def owner "01234567-89ab-cdef-0123-456789abcdef")
(def peer "11234567-89ab-cdef-0123-456789abcdef")
(def editor "21234567-89ab-cdef-0123-456789abcdef")

(deftest footer-preserves-ownership-and-deduplicates-contributors
  (let [original (pr/append-footer "Description\n\n```sh\necho '$HOME'\n```" nil owner [peer owner peer])
        edited (pr/append-footer "New body\n" original editor [peer editor])
        refreshed (pr/append-footer edited edited editor [peer])]
    (is (= original (pr/append-footer original original owner [peer])))
    (is (= edited refreshed))
    (is (= {:body "New body\n\n\n", :owner owner, :contributors [peer editor]}
           (pr/split-footer edited)))
    (is (str/includes? original "echo '$HOME'"))
    (is (= 1 (count (re-seq #"### Codex sessions" refreshed))))
    (is (str/ends-with? refreshed (str pr/end-marker "\n")))))

(deftest names-and-worktrees-survive-renames-and-unavailable-local-data
  (let [original (pr/append-footer "Body" nil owner []
                                   {owner {:title "First title"
                                           :titles ["Generated description" "First title"]
                                           :worktrees ["/work/first"]}})
        updated (pr/append-footer "Revised" original editor []
                                  {owner {:title "Current title"
                                          :titles ["Current title"]
                                          :worktrees ["/work/second"]}})
        preserved (pr/append-footer updated updated editor [] {})]
    (is (str/includes? updated "In charge: **Current title**"))
    (is (str/includes? updated "Earlier titles/descriptions: Generated description; First title"))
    (is (str/includes? updated "    - /work/first\n    - /work/second"))
    (is (= updated preserved))
    (is (= updated (pr/append-footer updated updated editor []
                                     {owner {:title "Current title"
                                          :titles ["Current title"]
                                             :worktrees ["/work/second"]}})))))

(deftest missing-titles-remain-renderable-and-stable-across-refreshes
  (let [empty-details {owner {:titles [], :worktrees []}}
        original (pr/append-footer "Body" nil owner [] empty-details)
        refreshed (pr/append-footer original original owner [] empty-details)
        named (pr/append-footer "Body" nil owner []
                                {owner {:title "Recorded name", :titles ["Recorded name"], :worktrees []}})
        unavailable (pr/append-footer named named owner [] empty-details)]
    (is (= original refreshed))
    (is (= refreshed (pr/append-footer refreshed refreshed owner [] empty-details)))
    (is (= named unavailable))
    (is (str/includes? unavailable "Recorded name"))))

(deftest session-metadata-cannot-inject-markdown-or-footer-markers
  (let [title (str "Title *markup*\n" pr/end-marker)
        body (pr/append-footer "Body" nil owner []
                               {owner {:title title, :titles [title], :worktrees ["/work/<script>`"]}})]
    (is (= title (get-in (pr/split-footer body) [:details 0 :title])))
    (is (= 1 (count (re-seq (re-pattern pr/end-marker) body))))
    (is (str/includes? body "&#42;markup&#42;"))
    (is (not (str/includes? body "<script>")))))

(deftest malformed-provenance-is-never-erased
  (doseq [body [(str "body\n" pr/start-marker)
                (str pr/end-marker)
                (str (pr/append-footer "body" nil owner [])
                     (pr/append-footer "body" nil owner []))
                (str pr/start-marker "\n<!-- {} -->\n" pr/end-marker)]]
    (is (thrown? Exception (pr/append-footer "replacement" body owner [])))))

(deftest cli-flags-are-unambiguous-and-safe
  (is (= {:forward ["--title" "a $title" "--draft"],
          :contributors [peer],
          :selectors [],
          :body-source [:file "a file.md"],
          :values {"--title" "a $title"},
          :session owner}
         (pr/parse-args ["--title=a $title" "-Fa file.md" "--draft"
                         "--session" owner "--contributor" peer])))
  (doseq [args [["--body" "a" "-F" "b"] ["--title"] ["--contributor" "not-a-session"]
                ["--repo" "other/repo"] ["--fill"] ["--attach" "image.png"] ["--web"]]]
    (is (thrown? Exception (pr/parse-args args))))
  (is (= "\nfrom stdin\n" (binding [*in* (java.io.StringReader. "\nfrom stdin\n")]
                               (pr/body-text [:file "-"])))))

(deftest writes-use-remote-pr-identity-and-provenance
  (let [calls (atom [])
        previous (pr/append-footer "Original body" nil owner [])
        existing {:body previous,
                  :url "https://github.com/tolgraven/tolgrAVen/pull/1",
                  :commits [{:messageBody (str "Work\n\nCodex-Session: " peer)}]}]
    (with-redefs [pr/local-session-details (constantly {})
                  pr/current-session (constantly editor)
                  pr/run! (fn [command & [options]]
                            (swap! calls conj [command options])
                            {:exit 0, :out (json/generate-string existing)})]
      (pr/write-pr! "edit" ["1" "--body" "Replacement" "--add-label" "tools"]))
    (is (= ["gh" "pr" "view" "--repo" pr/repository "1" "--json" "body,commits,url"]
           (ffirst @calls)))
    (is (= ["gh" "pr" "edit" "--repo" pr/repository (:url existing)
            "--add-label" "tools" "--body-file" "-"] (first (last @calls))))
    (is (= owner (:owner (pr/split-footer (:in (second (last @calls)))))))
    (is (= [editor peer] (:contributors (pr/split-footer (:in (second (last @calls)))))))
    (is (not-any? #(= "git" (ffirst %)) @calls))))

(deftest creation-collects-only-the-selected-base-range
  (let [calls (atom [])]
    (with-redefs [pr/local-session-details (constantly {})
                  pr/current-session (constantly owner)
                  pr/run! (fn [command & [options]]
                            (swap! calls conj [command options])
                            {:exit 0,
                             :out (cond
                                    (= "branch" (second command)) "codex/work\n"
                                    (= "log" (second command)) (str "Codex-Session: " peer "\n")
                                    :else "")})]
      (pr/write-pr! "create" ["--title" "Title" "--body" "Body" "--base" "release"]))
    (is (some #(= ["git" "log" "origin/release..HEAD" "--format=%(trailers:key=Codex-Session)"]
                  (first %)) @calls))
    (is (= [peer] (:contributors (pr/split-footer (:in (second (last @calls)))))))))

(deftest preflight-and-provider-failures-do-not-write
  (doseq [args [["--title" "No body"] ["--body" "Body" "--head" "other"]]]
    (let [writes (atom [])]
      (with-redefs [pr/local-session-details (constantly {})
                  pr/current-session (constantly owner)
                    pr/run! (fn [command & _]
                              (when (= "gh" (first command)) (swap! writes conj command))
                              {:out "codex/work"})]
        (is (thrown? Exception (pr/write-pr! "create" args))))
      (is (empty? @writes))))
  (with-redefs [pr/local-session-details (constantly {})
                  pr/current-session (constantly owner)
                pr/run! (fn [& _] (throw (ex-info "provider failed" {:exit 17})))]
    (is (= 17 (try (pr/write-pr! "edit" ["1"]) (catch Exception e (:exit (ex-data e))))))))

(deftest installed-hook-records-real-commits-and-preserves-amended-contributors
  (let [root (str (fs/absolutize "."))
        directory (fs/create-temp-dir {:prefix "pr-session-test-"})
        git (fn [session & args]
              (process/check @(process/process (into ["git" "-C" (str directory)] args)
                                               {:out :string,
                                                :err :string,
                                                :extra-env {"CODEX_THREAD_ID" session, "CODEX_SESSION_ID" ""}})))]
    (try
      (git "" "init" "-b" "master")
      (git "" "config" "user.name" "Session Test")
      (git "" "config" "user.email" "session@example.invalid")
      (fs/create-dirs (fs/path directory ".githooks"))
      (fs/copy (fs/path root ".githooks/prepare-commit-msg")
               (fs/path directory ".githooks/prepare-commit-msg"))
      (fs/create-sym-link (fs/path directory "scripts") (fs/path root "scripts"))
      (git "" "config" "core.hooksPath" ".githooks")
      (git owner "commit" "--allow-empty" "-m" "First work")
      (is (= [owner] (pr/trailer-sessions [(:out (git "" "log" "-1" "--format=%B"))])))
      (git peer "commit" "--amend" "--allow-empty" "--no-edit")
      (is (= [owner peer] (pr/trailer-sessions [(:out (git "" "log" "-1" "--format=%B"))])))
      (git peer "commit" "--amend" "--allow-empty" "--no-edit")
      (is (= [owner peer] (pr/trailer-sessions [(:out (git "" "log" "-1" "--format=%B"))])))
      (git "" "commit" "--allow-empty" "-m" "Human work")
      (is (= "Human work\n\n" (:out (git "" "log" "-1" "--format=%B"))))
      (finally (fs/delete-tree directory)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'pr-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
