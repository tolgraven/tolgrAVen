(ns website.github-detail-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as string]
            [clj-http.client :as http]
            [website.config :as config]
            [website.modules.github.api :as api]
            [website.modules.github.detail :as detail]
            [website.modules.github.provider :as provider]
            [website.modules.github.settings :as settings]
            [website.modules.github.detail-schema :as schema]
            [website.validation :as validation])
  (:import [java.io ByteArrayInputStream]))

(def sha (apply str (repeat 40 "a")))
(def parent (apply str (repeat 40 "b")))
(def parameters {:user "test", :repo "repo", :sha sha})
(def commit
  {:sha sha
   :author {:login "test", :avatar_url nil, :provider-private "omit"}
   :commit {:message "Fix the original source"
            :author {:name "Test", :email "private@example.test", :date "2026-10-14T10:00:00Z"}
            :committer {:name "Test", :email "private@example.test", :date "2026-10-14T10:00:00Z"}
            :verification {:signature "omit"}}
   :parents [{:sha parent, :url "omit"}]
   :stats {:additions 1, :deletions 1, :total 2}
   :files [{:filename "after.clj", :previous_filename "before.clj", :status "renamed"
            :additions 1, :deletions 1, :changes 2, :patch "@@ -1 +1 @@\n-old\n+new\n"
            :raw_url "https://github.com/test/repo/raw/file.clj", :private-extension "omit"}]})

(use-fixtures :each
  (fn [run!]
    (with-redefs-fn {(ns-resolve 'website.modules.github.detail '*cache) (atom {})
                    #'settings/selection! (constantly {:user "test", :repo "repo"})}
      run!)))

(deftest only-consumed-public-fields-cross-the-detail-boundary
  (let [result (detail/public-detail "test" "repo" commit)]
    (is (nil? (validation/explain schema/result result)))
    (is (= (get-in commit [:files 0 :patch]) (get-in result [:files 0 :patch])))
    (is (= "before.clj" (get-in result [:files 0 :previous_filename])))
    (is (= parent (get-in result [:parents 0 :sha])))
    (is (= (str "https://github.com/test/repo/commit/" sha) (:html_url result)))
    (is (not (string/includes? (pr-str result) "private@example.test")))
    (is (not (string/includes? (pr-str result) "omit")))))

(deftest current-selection-and-inputs-are-checked-before-provider-reads
  (with-redefs [provider/request! (fn [& _] (throw (Exception. "Provider must not be read")))]
    (is (= 404 (:status (detail/response! (assoc parameters :repo "other")))))
    (doseq [changes [{:user "../test"} {:repo ".."} {:sha "HEAD"} {:page 0} {:page 31}
                     {:kind :source, :path "../private"} {:kind :source, :path "valid", :sha "aaaaaaa"}]]
      (is (= 400 (:status (detail/response! (merge parameters changes))))))))

(deftest cached-details-recheck-visibility-and-do-not-refetch-patches
  (let [requests (atom [])
        public? (atom true)]
    (with-redefs [provider/request! (fn [_ path options]
                                     (swap! requests conj [path options])
                                     (if (= path "/repos/test/repo") {:private (not @public?)} commit))]
      (is (= 200 (:status (detail/response! parameters))))
      (is (= 200 (:status (detail/response! (assoc parameters :user "TEST" :sha (string/upper-case sha))))))
      (is (= 1 (count (filter #(string/includes? (first %) "/commits/") @requests))))
      (is (= 3 (count (filter #(= "/repos/test/repo" (first %)) @requests))))
      (reset! public? false)
      (is (= 404 (:status (detail/response! parameters))))
      (is (= "no-store" (get-in (detail/response! parameters) [:headers "Cache-Control"]))))))

(deftest privatization-after-reading-discards-the-fresh-detail
  (let [checks (atom 0)]
    (with-redefs [provider/request! (fn [_ path _]
                                     (if (= path "/repos/test/repo")
                                       {:private (> (swap! checks inc) 1)} commit))]
      (let [response (detail/response! parameters)]
        (is (= 404 (:status response)))
        (is (not (contains? (:body response) :files)))))))

(deftest malformed-or-mismatched-provider-data-stays-unavailable
  (doseq [value [(assoc commit :sha parent) (dissoc commit :stats)
                (dissoc commit :files) (dissoc commit :parents) (assoc commit :files "bad")]]
    (with-redefs-fn {(ns-resolve 'website.modules.github.detail '*cache) (atom {})
                    #'provider/request! (fn [_ path _] (if (= path "/repos/test/repo") {:private false} value))}
      #(is (= 503 (:status (detail/response! parameters)))))))

(deftest metadata-pages-and-immutable-file-paths-share-the-server-boundary
  (let [requests (atom [])
        source {:type "file", :encoding "base64", :content "eAo=", :size 2}]
    (with-redefs [provider/request! (fn [_ path options]
                                     (swap! requests conj [path options])
                                     (cond
                                       (= path "/repos/test/repo") {:private false}
                                       (string/includes? path "/contents/") (assoc source :download_url "omit")
                                       :else commit))]
      (is (= 200 (:status (detail/response! (assoc parameters :page 2)))))
      (is (= {:page 2, :per_page 100} (get-in (first (filter #(string/includes? (first %) "/commits/") @requests)) [1 :query-params])))
      (is (= source (:body (detail/response! (assoc parameters :kind :source :path "a b/#c+λ.clj")))))
      (is (some #{[(str "/repos/test/repo/contents/a%20b/%23c%2B%CE%BB.clj") {:query-params {:ref sha}}]} @requests)))))

(deftest patch-transport-uses-server-auth-and-bounds-before-decoding
  (let [options (atom nil)
        patch "@@ -1 +1 @@\n-old\n+new\n"]
    (with-redefs [config/env {:github-token "fixture-secret"}
                  http/request (fn [request]
                                 (reset! options request)
                                 {:status 200, :body (ByteArrayInputStream. (.getBytes patch "UTF-8"))})]
      (is (= patch (provider/request-patch! (str "/repos/test/repo/commits/" sha))))
      (is (= "Bearer fixture-secret" (get-in @options [:headers "Authorization"])))
      (is (= "application/vnd.github.patch" (get-in @options [:headers "Accept"])))
      (is (false? (:follow-redirects @options)))
      (is (= 10000 (:socket-timeout @options))))
    (with-redefs [http/request (fn [_]
                                {:status 200, :body (ByteArrayInputStream. (byte-array 1048577))})]
      (is (= :body-limit (try (provider/request-patch! (str "/repos/test/repo/commits/" sha))
                             (catch Exception error (:reason (ex-data error)))))))))

(deftest rate-limits-keep-retry-timing-and-redact-provider-payloads
  (with-redefs [provider/request! (fn [& _]
                                   (throw (ex-info "fixture-secret provider body"
                                                   {:upstream-status 403
                                                    :retry-at (+ 120 (quot (System/currentTimeMillis) 1000))})))]
    (let [response (detail/response! parameters)]
      (is (= 503 (:status response)))
      (is (<= 1 (Long/parseLong (get-in response [:headers "Retry-After"])) 120))
      (is (not (string/includes? (pr-str response) "fixture-secret"))))))

(deftest route-handlers-forward-only-their-consumed-typed-fields
  (let [handlers (into {} (map (fn [[path operations]] [path (get-in operations [:get :handler])]) (api/routes)))
        received (atom nil)]
    (with-redefs [detail/response! #(reset! received %)]
      ((handlers "/github/commits/:sha") {:parameters {:path {:sha sha}
                                                       :query {:user "test", :repo "repo", :page 2, :kind :source, :path "ignored"}}})
      (is (= (assoc parameters :page 2) @received))
      ((handlers "/github/commits/:sha/patch") {:parameters {:path {:sha sha}, :query {:user "test", :repo "repo"}}})
      (is (= (assoc parameters :kind :patch) @received))
      ((handlers "/github/contents") {:parameters {:query {:user "test", :repo "repo", :ref sha, :path "a.clj"}}})
      (is (= (assoc parameters :kind :source :path "a.clj") @received)))))
