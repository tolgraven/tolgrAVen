(ns tolgraven.development-document-test
  (:require [clojure.test :refer [deftest is]]
            [tolgraven.env :as environment]
            [tolgraven.middleware :as middleware]
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
