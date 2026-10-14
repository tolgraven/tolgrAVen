(ns website.content-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clj-http.client :as http]
            [website.content.contract :as contract]
            [website.page-router :as pages]
            [website.content.service :as content]))

(use-fixtures :each
  (fn [f]
    (reset! content/*cache {})
    (reset! content/*inflight {})
    (try (f)
         (finally (reset! content/*cache {}) (reset! content/*inflight {})))))

(deftest backend-seed-replaces-all-former-static-content
  (with-redefs [content/settings (constantly {})]
    (let [bundle (content/bundle!)]
      (is (= (set contract/sections) (set (keys (:content bundle)))))
      (is (= "Building experiences" (get-in bundle [:content :intro :title])))
      (is (= [:state [:contact-form :show?] true] (get-in bundle [:content :intro :buttons 1 1])))
      (is (= :cv (get-in bundle [:content :cv :heading :target])))
      (is (= :education (get-in bundle [:content :cv :cv :timeline 0 :category]))))))

(deftest requests-are-server-authenticated-bounded-and-cached-per-section
  (let [calls (atom [])]
    (with-redefs [content/settings (constantly {:url "https://cms.test/" :token "private-token"})
                  http/get (fn [url opts]
                             (swap! calls conj [url opts])
                             {:status 200 :body {:version 1 :content {:document {:title "From CMS"}}}})]
      (is (= "From CMS" (get-in (content/bundle! [:document]) [:content :document :title])))
      (content/bundle! [:document])
      (is (= 1 (count @calls)))
      (is (= "https://cms.test/api/site-content" (ffirst @calls)))
      (is (= "Bearer private-token" (get-in @calls [0 1 :headers "Authorization"])))
      (is (= "document" (get-in @calls [0 1 :query-params "keys"])))
      (is (= 3000 (get-in @calls [0 1 :conn-timeout])))
      (is (= 10000 (get-in @calls [0 1 :socket-timeout])))
      (is (not (.contains (pr-str (content/response [:document])) "private-token"))))))

(deftest unknown-content-and-upstream-failure-are-contained
  (with-redefs [content/settings (constantly {:url "https://cms.test" :token "secret"})
                http/get (fn [& _] (throw (Exception. "Authorization: secret")))]
    (is (= 400 (:status (content/response ["admin"]))))
    (is (= 503 (:status (content/response [:intro]))))
    (is (not (.contains (pr-str (content/response [:intro])) "secret")))))

(deftest hydration-cannot-close-its-script-element
  (let [bundle {:version 1 :content {:intro {:text "</script><script>alert(1)</script>&"}}}
        encoded (content/hydration-json bundle)]
    (is (not (.contains encoded "<")))
    (is (= bundle (json/read-str encoded :key-fn keyword)))))

(deftest route-bootstrap-uses-the-module-content-manifest
  (is (every? (set (contract/keys-for-route :cv)) (get contract/module-content :cv)))
  (is (not (contains? (set (contract/keys-for-route :cv)) :strava)))
  (is (= contract/shell-content (contract/keys-for-route :unknown))))

(deftest startup-content-is-local-and-refresh-is-atomic
  (let [original @content/*immediate
        calls (atom 0)
        fail? (atom false)]
    (try
      (reset! content/*immediate nil)
      (with-redefs [content/settings (constantly {:url "https://startup.test"})
                    pages/immediate-keys (constantly [:document :footer])
                    http/get (fn [& _]
                               (swap! calls inc)
                               (if @fail? (throw (Exception. "offline"))
                                   {:status 200 :body {:version 1 :content
                                                      {:document {:title (str "Version " @calls)}
                                                       :footer [{:title "Test"}]}}}))]
        (content/refresh-immediate!)
        (reset! fail? true)
        (dotimes [_ 5]
          (is (= "Version 1" (get-in (content/fresh-bundle! [:document]) [:content :document :title]))))
        (is (= 1 @calls) "Requests for startup content must never contact the CMS")
        (is (thrown? Exception (content/refresh-immediate!)))
        (is (= "Version 1" (get-in (content/immediate-content) [:document :title])))
        (reset! fail? false)
        (content/refresh-immediate!)
        (is (= "Version 3" (get-in (content/fresh-bundle! [:document]) [:content :document :title]))))
      (finally (reset! content/*immediate original)))))

(deftest startup-declarations-include-common-shell-and-module-heading
  (is (every? (set (pages/immediate-keys)) [:document :header :footer :blog]))
  (is (not (contains? (set (pages/immediate-keys)) :gallery))))

(deftest public-content-identifies-its-source-without-exposing-credentials
  (doseq [[settings expected] [[{} "seed"] [{:url "https://cms.test" :token "private"} "strapi"]]]
    (with-redefs [content/settings (constantly settings)
                  content/bundle! (constantly {:version 1 :content {}})]
      (let [response (content/response nil)]
        (is (= expected (get-in response [:headers "X-Content-Source"])))
        (is (not (.contains (pr-str response) "private")))))))

(deftest bootstrap-omits-unpublished-optional-sections
  (let [unsupported #{:github-settings :codex-profile}
        seed (json/read-str (slurp (io/resource "content-seed.json")) :key-fn keyword)
        calls (atom [])]
    (is (not-any? unsupported contract/sections))
    (doseq [module [:github :codex-profile]]
      (is (empty? (get contract/module-content module)))
      (is (empty? (get contract/module-dependencies module))))
    (with-redefs [content/settings (constantly {:url "https://optional-sections.test"})
                  http/get (fn [_ options]
                             (let [ks (mapv keyword (string/split (get-in options [:query-params "keys"]) #","))]
                               (swap! calls conj ks)
                               (if (some unsupported ks)
                                 {:status 400}
                                 {:status 200
                                  :body {:version contract/version
                                         :content (select-keys seed ks)}})))]
      (let [response (content/response nil)]
        (is (= 200 (:status response)))
        (is (= (set contract/sections) (set (keys (get-in response [:body :content])))))
        (is (= "Building experiences" (get-in response [:body :content :intro :title])))
        (is (= [contract/sections] @calls))
        (is (= (:body response) (contract/checked-bundle (:body response) contract/sections)))))))

(deftest slow-unavailable-section-does-not-block-healthy-content
  (let [started (promise)
        release (promise)
        *calls (atom [])]
    (with-redefs [content/settings (constantly {:url "https://isolated.test"})
                  http/get (fn [_ options]
                             (let [key (get-in options [:query-params "keys"])]
                               (swap! *calls conj key)
                               (if (= "docs" key)
                                 (do (deliver started true)
                                     (deref release 5000 nil)
                                     {:status 400})
                                 {:status 200
                                  :body {:version contract/version
                                         :content {:intro {:title "Healthy"}}}})))]
      (let [failed (future (content/response [:docs]))]
        (try
          (is (= true (deref started 1000 :timeout)))
          (let [healthy (future (content/response [:intro]))
                response (deref healthy 1000 :timeout)]
            (is (= 200 (:status response)))
            (is (= "Healthy" (get-in response [:body :content :intro :title]))))
          (finally (deliver release true)))
        (is (= 503 (:status (deref failed 1000 {}))))
        (is (= ["docs" "intro"] @*calls))))))

(deftest concurrent-section-consumers-share-one-read-and-retry-after-failure
  (let [started (promise)
        release (promise)
        *calls (atom 0)
        *fail? (atom true)]
    (with-redefs [content/settings (constantly {:url "https://shared.test"})
                  http/get (fn [& _]
                             (swap! *calls inc)
                             (deliver started true)
                             (deref release 5000 nil)
                             (if @*fail?
                               {:status 400}
                               {:status 200
                                :body {:version contract/version
                                       :content {:intro {:title "Recovered"}}}}))]
      (let [first-read (future (content/response [:intro]))]
        (is (= true (deref started 1000 :timeout)))
        ;; Claim the same pending section synchronously so the sharing assertion
        ;; does not depend on the second future's scheduling.
        (let [claimed (#'content/acquire-sections! "https://shared.test" [:intro] (System/currentTimeMillis))
              pending (:pending claimed)]
          (try
            (is (false? (:owned? claimed)))
            (is (= 1 @*calls))
            (finally (deliver release true)))
          (is (= 503 (:status (deref first-read 1000 {}))))
          (is (:error (deref pending 1000 {})))))
      (reset! *fail? false)
      (is (= "Recovered" (get-in (content/response [:intro]) [:body :content :intro :title])))
      (is (= 2 @*calls)))))

(deftest failed-bundle-does-not-own-healthy-subset-requests
  (let [started (promise)
        release (promise)]
    (with-redefs [content/settings (constantly {:url "https://overlapping.test"})
                  http/get (fn [_ options]
                             (if (= "intro,docs" (get-in options [:query-params "keys"]))
                               (do (deliver started true)
                                   (deref release 5000 nil)
                                   {:status 400})
                               {:status 200
                                :body {:version contract/version
                                       :content {:intro {:title "Independent"}}}}))]
      (let [failed (future (content/response [:intro :docs]))]
        (try
          (is (= true (deref started 1000 :timeout)))
          (let [healthy (future (content/response [:intro]))
                response (deref healthy 1000 :timeout)]
            (is (= 200 (:status response)))
            (is (= "Independent" (get-in response [:body :content :intro :title]))))
          (finally (deliver release true)))
        (is (= 503 (:status (deref failed 1000 {}))))
        (is (empty? (get @content/*inflight "https://overlapping.test")))))))

(deftest module-startup-content-failure-does-not-block-shell-or-other-headings
  (let [original @content/*immediate
        *calls (atom [])]
    (try
      (reset! content/*immediate nil)
      (with-redefs [content/settings (constantly {:url "https://startup-isolation.test"})
                    pages/immediate-keys (constantly [:document :footer :blog :docs])
                    http/get (fn [_ options]
                               (let [ks (get-in options [:query-params "keys"])]
                                 (swap! *calls conj ks)
                                 (case ks
                                   "document,footer" {:status 200
                                                      :body {:version contract/version
                                                             :content {:document {:title "Shell"}, :footer []}}}
                                   "blog" {:status 400}
                                   "docs" {:status 200
                                           :body {:version contract/version
                                                  :content {:docs {:heading {:title "Docs"}}}}})))]
        (is (= {:document {:title "Shell"}, :footer []} (content/refresh-immediate! false)))
        (is (= ["document,footer"] @*calls) "Optional module reads do not gate startup")
        (let [warm (content/refresh-immediate!)]
          (is (= "Shell" (get-in warm [:document :title])))
          (is (= "Docs" (get-in warm [:docs :heading :title])))
          (is (not (contains? warm :blog)))
          (is (= warm (content/immediate-content))))
        (swap! content/*immediate assoc-in [:content :blog] {:heading {:title "Retained"}})
        (is (= "Retained" (get-in (content/refresh-immediate!) [:blog :heading :title]))))
      (finally (reset! content/*immediate original)))))

(deftest interrupted-module-warmup-stops-without-publishing
  (let [original @content/*immediate
        snapshot {:source "https://interrupted.test", :content {:document {:title "Retained"}}}
        *calls (atom [])]
    (try
      (reset! content/*immediate snapshot)
      (with-redefs [content/settings (constantly {:url "https://interrupted.test"})
                    pages/immediate-keys (constantly [:document :blog :docs])
                    http/get (fn [_ options]
                               (let [key (get-in options [:query-params "keys"])]
                                 (swap! *calls conj key)
                                 (if (= "blog" key)
                                   (throw (InterruptedException. "Stopped owner"))
                                   {:status 200
                                    :body {:version contract/version
                                           :content {:document {:title "New"}}}})))]
        (let [outcome (try (content/refresh-immediate!)
                           {:interrupted? false}
                           (catch InterruptedException _
                             {:threw? true, :interrupted? (.isInterrupted (Thread/currentThread))}))]
          ;; Reporting itself may use interruptible queues. Observe cancellation
          ;; before returning the thread to the test reporter.
          (Thread/interrupted)
          (is (:threw? outcome))
          (is (:interrupted? outcome)))
        (is (= ["document" "blog"] @*calls))
        (is (= snapshot @content/*immediate)))
      (finally (Thread/interrupted) (reset! content/*immediate original)))))
