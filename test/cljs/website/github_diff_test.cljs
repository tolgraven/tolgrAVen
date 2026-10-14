(ns website.github-diff-test
  (:require [cljs.test :refer-macros [deftest is]]
            [website.modules.github.diff :as diff]
            [website.modules.github.diff-events :as events]
            [re-frame.registrar :as registrar]))

(def parent (apply str (repeat 40 "a")))
(def sha (apply str (repeat 40 "b")))
(def commit {:sha sha, :parents [{:sha parent}]})

(deftest immutable-revision-paths
  (is (= (str "/neovim/github/user/repo/" sha) (diff/page-url ["user" "repo" sha])))
  (doseq [revision [["u/evil" "repo" sha] ["u" ".." sha] ["u" "r" "HEAD"] ["u" "r" sha "extra"]]]
    (is (nil? (diff/page-url revision))))
  (is (= (str "https://api.github.com/repos/u/r/contents/a%20b/%23c.clj?ref=" sha)
         (diff/contents-url ["u" "r" sha] {:sha sha, :path "a b/#c.clj"})))
  (doseq [path ["../x" "/x" "x//y" "x\\y" "x/./y"]] (is (not (diff/safe-path? path)))))

(deftest revision-sides-handle-add-delete-rename-and-root
  (is (= {:before {:sha parent, :path "old.clj"}, :after {:sha sha, :path "new.clj"}}
         (diff/sides commit {:filename "new.clj", :previous_filename "old.clj", :status "renamed"})))
  (is (nil? (:before (diff/sides commit {:filename "x", :status "added"}))))
  (is (nil? (:after (diff/sides commit {:filename "x", :status "removed"}))))
  (is (nil? (:before (diff/sides {:sha sha, :parents []} {:filename "x", :status "modified"})))))

(deftest text-decoding-preserves-newlines-and-rejects-binary
  (is (= "hello\n" (diff/decode-text {:type "file", :encoding "base64", :content "aGVsbG8K\n", :size 6})))
  (is (= "" (diff/decode-text {:type "file", :encoding "base64", :content "", :size 0})))
  (doseq [payload [{:type "file", :encoding "none", :content "", :size 2}
                   {:type "dir", :encoding "base64", :content "", :size 0}
                   {:type "file", :encoding "base64", :content "AA==", :size 1}
                   {:type "file", :encoding "base64", :content "/w==", :size 1}
                   {:type "file", :encoding "base64", :content "", :size 1048577}]]
    (is (try (diff/decode-text payload) false (catch :default _ true)))))

(defn event-effects [db event]
  ;; Invoke only the registered handler interceptor, before the effect executor.
  (:effects ((:before (last (registrar/get-handler :event (first event))))
             {:coeffects {:db db, :event event}})))

(deftest cached-pairs-and-asynchronous-sides-remain-distinct
  (let [revision ["u" "r" sha]
        initial (:db (event-effects {} [:github/diff-open revision]))
        metadata (event-effects initial [:github/diff-metadata revision 1
                                        (assoc commit :files [{:filename "x.clj", :status "modified"}])])
        requested (event-effects (:db metadata) [:github/diff-ensure revision])
        payload {:type "file", :encoding "base64", :content "eAo=", :size 2}
        before (:db (event-effects (:db requested) [:github/diff-source revision "x.clj" 1 :before payload]))
        after (:db (event-effects before [:github/diff-source revision "x.clj" 1 :after payload]))]
    (is (= 2 (count (:dispatch-n requested))))
    (is (= :loading (get-in before (events/state-path revision :files "x.clj" :status))))
    (is (= :ready (get-in after (events/state-path revision :files "x.clj" :status))))
    (is (= {:mode :diff, :selected "x.clj"} (get-in after (events/state-path revision :displayed))))
    (is (nil? (:dispatch-n (event-effects after [:github/diff-ensure revision]))))
    (is (nil? (event-effects initial [:github/diff-open revision])))))

(deftest superseded-file-failures-cannot-poison-a-retry
  (let [revision ["u" "r" sha]
        initial (:db (event-effects {} [:github/diff-open revision]))
        metadata (:db (event-effects initial [:github/diff-metadata revision 1
                                             (assoc commit :files [{:filename "x.clj", :status "modified"}])]))
        requested (:db (event-effects metadata [:github/diff-ensure revision]))
        failed (:db (event-effects requested [:github/diff-file-failed revision "x.clj" 1 {:status 500}]))
        retry (:db (event-effects failed [:github/diff-ensure revision]))
        stale (:db (event-effects retry [:github/diff-file-failed revision "x.clj" 1 {:status 500}]))
        payload {:type "file", :encoding "base64", :content "eAo=", :size 2}
        before (:db (event-effects stale [:github/diff-source revision "x.clj" 2 :before payload]))
        ready (:db (event-effects before [:github/diff-source revision "x.clj" 2 :after payload]))]
    (is (= retry stale))
    (is (= :ready (get-in ready (events/state-path revision :files "x.clj" :status))))
    (is (= "x\n" (get-in ready (events/content-path revision :files "x.clj" :after))))))

(deftest metadata-pages-use-the-same-explicit-page-size
  (let [revision ["u" "r" sha]
        effects (event-effects {} [:github/diff-metadata revision 1
                                  (assoc commit :files (vec (repeat 100 {:filename "x"})))])]
    (is (= (str (diff/commit-url revision) "?per_page=100&page=2") (get-in effects [:dispatch 1 :uri])))
    (is (= :loading (get-in (:db effects) (events/state-path revision :metadata :status))))))
