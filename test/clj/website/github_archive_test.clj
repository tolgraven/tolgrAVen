(ns website.github-archive-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [website.config :as config]
            [website.modules.github.provider :as provider]
            [website.modules.github.sync :as sync]
            [website.modules.github.activity :as activity]
            [website.modules.github.archive :as archive]
            [website.platform.supabase :as platform]
            [website.module-services :as services])
  (:import [java.io ByteArrayInputStream]))

(def hash-a (apply str (repeat 40 "a")))
(def hash-b (apply str (repeat 40 "b")))
(def item {:sha hash-a
           :html_url (str "https://github.com/test/repo/commit/" hash-a)
           :author {:login "test", :avatar_url "https://avatars.githubusercontent.com/u/1"}
           :commit {:message "scss: theme: fix colours\n\nPreserve full body"
                    :author {:name "Test", :email "private@example.test", :date "2026-10-13T10:00:00Z"}
                    :committer {:date "2026-10-13T10:00:00Z"}}})
(def job {:owner "test", :repo "repo", :is_public true, :complete false
          :state {:stage "commits", :heads [], :pending [{:name "main", :sha hash-b}], :page 1}})

(deftest normalize-only-public-consumed-fields
  (let [row (sync/commit-row item)]
    (is (= "scss" (:category row)))
    (is (= "theme" (:subcategory row)))
    (is (= (get-in item [:commit :message]) (:message row)))
    (is (not (contains? row :email)))
    (is (= (dissoc item :commit)
           (dissoc (archive/provider-commit row) :commit)))))

(deftest immutable-head-full-pagination-and-unchanged-branch-skip
  (let [*requests (atom [])]
    (with-redefs [provider/request! (fn [_ path options]
                                     (if (= path "/repos/test/repo") {:private false}
                                         (do (swap! *requests conj [path options])
                                             (vec (repeat (if (= 1 (get-in options [:query-params :page])) 100 2) item)))))]
      (let [first-page (sync/step! job)
            second-page (sync/step! (assoc job :state (:state first-page)))]
        (is (= 2 (get-in first-page [:state :page])))
        (is (= [] (get-in second-page [:state :pending])))
        (is (= [{:name "main", :sha hash-b}] (get-in second-page [:state :heads])))
        (is (= [hash-b hash-b] (mapv #(get-in % [1 :query-params :sha]) @*requests)))))
    (with-redefs [provider/request! (fn [_ path _] (if (= path "/repos/test/repo") {:private false}
                                                    [{:name "main", :commit {:sha hash-a}}
                                                     {:name "new", :commit {:sha hash-b}}]))]
      (let [result (sync/step! (assoc job :state {:stage "tags", :page 1,
                                               :branches [{:name "main", :sha hash-a} {:name "new", :sha hash-b}]
                                               :heads [{:name "main", :sha hash-a}]}))]
        (is (some #{ {:name "new", :sha hash-b}} (get-in result [:state :pending])))))))

(deftest private-repositories-never-produce-rows
  (with-redefs [provider/request! (fn [& _] {:private true})]
    (let [result (sync/step! (assoc job :state {:stage "metadata"}))]
      (is (false? (:public result)))
      (is (empty? (:rows result)))
      (is (= "metadata" (get-in result [:state :stage]))))))

(deftest retry-keeps-checkpoint-and-rate-limits-do-not-hide-public-history
  (let [*writes (atom [])]
    (with-redefs [sync/target! (constantly {:owner "test", :repo "repo"})
                  provider/request! (fn [& _] (throw (ex-info "Rate limit" {:upstream-status 403})))
                  platform/request! (fn [_ path options]
                                      (if (= path "rpc/website_claim_github") {:body [job]}
                                          (do (swap! *writes conj (:form-params options)) {:body true})))]
      (sync/work!)
      (is (= (:state job) (:p_state (first @*writes))))
      (is (true? (:p_public (first @*writes))))
      (is (= [] (:p_rows (first @*writes))))
      (is (<= 60 (:p_delay (first @*writes)) 86400)))))

(deftest bounded-json-transport-and-no-redirects
  (let [*options (atom nil)]
    (with-redefs [config/env {:github-token "fixture-secret"}
                  http/request (fn [options]
                                 (reset! *options options)
                                 {:status 200, :body (ByteArrayInputStream. (.getBytes "{\"private\":false}" "UTF-8"))})]
      (is (= {:private false} (provider/request! :get "/repos/test/repo" {})))
      (is (= "https://api.github.com/repos/test/repo" (:url @*options)))
      (is (false? (:follow-redirects @*options)))
      (is (= "Bearer fixture-secret" (get-in @*options [:headers "Authorization"])))
      (is (thrown? Exception (provider/request! :get "https://evil.test" {}))))))

(deftest day-breakdown-rejects-invalid-identities-before-acquisition
  (is (thrown? Exception (activity/fetch-day! "../evil" "2026-10-13")))
  (is (thrown? Exception (activity/fetch-day! "test" "2026-02-30")))
  (is (= 400 (:status (activity/day-response! "test" "2026-02-30"))))
  (is (= 400 (:status (activity/day-response! "../evil" "2026-10-13")))))

(deftest public-commit-transport-does-not-require-authentication
  (with-redefs [config/env {}
                http/request (fn [options]
                               (is (not (contains? (:headers options) "Authorization")))
                               {:status 200, :body (ByteArrayInputStream. (.getBytes "[]" "UTF-8"))})]
    (is (= [] (provider/request! :get "/repos/test/repo/commits" {})))))

(deftest module-service-cleanup-and-disabled-inventory
  (is (= [] (services/start! {})))
  (let [*stopped (atom false)]
    (with-redefs [sync/start! (fn [] #(reset! *stopped true))]
      (let [cleanups (services/start! {:github {:start 'website.modules.github.sync/start!}})]
        (is (= 1 (count cleanups)))
        ((first cleanups))
        (is (true? @*stopped))))))

(deftest comparisons-page-beyond-the-unpaged-250-limit-and-fallback-safely
  (let [*paths (atom [])
        branched (assoc job :state {:stage "commits", :heads [{:name "main", :sha hash-a}]
                                   :pending [{:name "feature", :sha hash-b}], :page 1})]
    (with-redefs [provider/request! (fn [_ path options]
                                     (if (= path "/repos/test/repo") {:private false}
                                         (do (swap! *paths conj path)
                                             {:commits (vec (repeat (if (< (get-in options [:query-params :page]) 4) 100 1) item))}))) ]
      (let [results (take 4 (rest (iterate #(sync/step! (assoc branched :state (:state %))) {:state (:state branched)})))]
        (is (= 301 (reduce + (map #(count (:rows %)) results))))
        (is (empty? (get-in (last results) [:state :pending])))
        (is (every? #(= (str "/repos/test/repo/compare/" hash-a "..." hash-b) %) @*paths))))
    (with-redefs [provider/request! (fn [_ path _] (if (= path "/repos/test/repo") {:private false}
                                                    (throw (ex-info "No common ancestor" {:upstream-status 404}))))]
      (let [fallback (sync/step! branched)]
        (is (= 1 (get-in fallback [:state :page])))
        (is (true? (get-in fallback [:state :full?])))
        (is (empty? (:rows fallback)))))))

(deftest privatization-during-a-checkpoint-hides-history-before-reading-more
  (let [*paths (atom [])]
    (with-redefs [provider/request! (fn [_ path _]
                                     (swap! *paths conj path)
                                     (if (= path "/repos/test/repo") {:private true} [item]))]
      (let [result (sync/step! job)]
        (is (false? (:public result)))
        (is (empty? (:rows result)))
        (is (= ["/repos/test/repo"] @*paths))))))

(deftest tag-only-ancestry-enters-the-same-checkpoint-queue
  (with-redefs [provider/request! (fn [_ path _]
                                   (case path
                                     "/repos/test/repo" {:private false}
                                     "/repos/test/repo/tags" [{:name "old-release", :commit {:sha hash-b}}]))]
    (let [result (sync/step! (assoc job :state {:stage "tags", :page 1, :branches [], :heads []}))]
      (is (= [{:name "tag:old-release", :sha hash-b}] (get-in result [:state :pending]))))))

(deftest privatization-between-visibility-check-and-page-fetch-discards-the-page
  (let [*checks (atom 0)]
    (with-redefs [provider/request! (fn [_ path _]
                                     (if (= path "/repos/test/repo")
                                       {:private (> (swap! *checks inc) 1)} [item]))]
      (let [result (sync/step! job)]
        (is (false? (:public result)))
        (is (empty? (:rows result)))
        (is (= "metadata" (get-in result [:state :stage])))))))

(deftest typed-search-cursors-reject-invalid-times-and-hashes
  (is (archive/cursor? (str "2020-01-01T00:00:00+00:00|" hash-a)))
  (doseq [value [nil "" (str "bad-time|" hash-a) "2020-01-01T00:00:00Z|bad" (str "2020-01-01T00:00:00Z|" hash-a "|extra")]]
    (is (not (archive/cursor? value)))))

(deftest backfill-cli-rejects-incomplete-or-extra-arguments-before-config-start
  (is (thrown? Exception (sync/-main "only-owner")))
  (is (thrown? Exception (sync/-main "owner" "repo" "extra"))))
