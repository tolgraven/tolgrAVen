(ns website.development-document-test
  (:require [clojure.test :refer [deftest is]]
            [website.env :as environment]
            [website.config :as config]
            [website.dev.shadow :as startup]
            [ring.mock.request :as mock]
            [website.middleware :as middleware]
            [optimus.assets :as assets]))

(deftest only-document-owned-requests-wait
  (let [*calls (atom [])]
    (with-redefs [environment/defaults {:document-ready! #(swap! *calls conj :ready)}]
      (let [handler (middleware/wrap-document-readiness
                      (fn [request] (swap! *calls conj (:uri request)) {:status 200}))]
        (doseq [uri ["/" "/cv" "/blog/test" "/api/page-return-template"
                     "/js/document/head.js" "/bundles/abc/document-entrance.js"]]
          (reset! *calls [])
          (is (= 200 (:status (handler {:uri uri, :request-method :get}))))
          (is (= [:ready uri] @*calls)))
        (doseq [uri ["/img/hero.avif" "/css/main.css" "/api/browser-resources"
                     "/js/compiled/out/main.js"]]
          (reset! *calls [])
          (handler {:uri uri, :request-method :get})
          (is (= [uri] @*calls)))))))

(deftest failed-document-release-does-not-serve-old-output
  (with-redefs [environment/defaults {:document-ready! #(throw (ex-info "failed release" {:status 503}))}]
    (let [*served? (atom false)
          handler (middleware/wrap-document-readiness (fn [_] (reset! *served? true)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed release"
                           (handler {:uri "/js/document/head.js", :request-method :get})))
      (is (false? @*served?)))))

(deftest pending-document-scripts-cannot-enter-development-inventory
  (let [*bundles (atom [])]
    (with-redefs [environment/defaults {:document-ready? (constantly false)}
                  assets/load-bundle (fn [& _] [])
                  assets/load-bundles (fn [_ bundles] (swap! *bundles conj bundles) [])]
      (doall (middleware/get-bundled-assets))
      (is (not-any? #(= middleware/document-bundles %) @*bundles)))
    (reset! *bundles [])
    (with-redefs [environment/defaults {:document-ready? (constantly true)}
                  assets/load-bundle (fn [& _] [])
                  assets/load-bundles (fn [_ bundles] (swap! *bundles conj bundles) [])]
      (doall (middleware/get-bundled-assets))
      (is (some #(= middleware/document-bundles %) @*bundles)))))

(deftest html-negotiation-gates-dotted-pages-but-never-static-media-or-api
  (let [*calls (atom [])]
    (with-redefs [environment/defaults {:document-ready! #(swap! *calls conj :ready)}]
      (let [handler (middleware/wrap-document-readiness (fn [_] {:status 200}))]
        (handler (-> (mock/request :get "/docs/README.md")
                     (mock/header "accept" "text/html,application/xhtml+xml;q=0.9")))
        (is (= [:ready] @*calls))
        (doseq [uri ["/img/logo.svg" "/img/hero.avif" "/css/main.css"
                     "/js/compiled/out/main.js" "/api/status.json"]]
          (reset! *calls [])
          (handler (-> (mock/request :get uri) (mock/header "accept" "text/html")))
          (is (empty? @*calls) (str uri " remains nonblocking even with HTML Accept")))))))

(deftest actual-base-stack-retains-service-unavailable-for-release-failure-and-timeout
  (let [previous @startup/*startup
        *served? (atom false)]
    (try
      (with-redefs [environment/defaults {:middleware identity
                                         :document-ready! startup/await-document!}
                    config/env {:test true, :https-required false}
                    middleware/wrap-optimus identity
                    startup/document-wait-ms 1]
        (let [handler (middleware/wrap-base (fn [_] (reset! *served? true) {:status 200}))]
          (doseq [failure [nil (ex-info "fixture release failure" {})]]
            (let [ready (promise)]
              (reset! startup/*startup {:document-ready ready})
              (when failure (deliver ready failure))
              (let [response (handler (-> (mock/request :get "/docs/README.md")
                                          (mock/header "accept" "text/html")))]
                (is (= 503 (:status response)))
                (is (re-find #"HTTP 503" (:body response)))
                (is (false? @*served?) "Neither timeout nor release failure serves old markup"))))))
      (finally (reset! startup/*startup previous)))))
