(ns tolgraven.content-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [tolgraven.content.contract :as contract]
            [tolgraven.page-router :as pages]
            [tolgraven.content.service :as content]))

(use-fixtures :each (fn [f] (reset! content/*cache {}) (f) (reset! content/*cache {})))

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
                                                       :footer {:copyright "Test"}}}}))]
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
