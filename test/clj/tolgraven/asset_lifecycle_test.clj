(ns tolgraven.asset-lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [tolgraven.config :as config]
            [tolgraven.middleware :as middleware]
            [tolgraven.middleware.asset-manifest :as manifest]))

(defn unexpected-work [& _]
  (throw (ex-info "Runtime attempted build-only asset work" {})))

(defn inventory [text]
  [{:path "/bundles/fixture.css", :contents text, :headers {}}])

(def request {:request-method :get, :uri "/bundles/fixture.css"})

(deftest production-imports-once-and-never-discovers-or-optimizes
  (let [calls (atom [])
        handler (with-redefs [config/env {}
                              ;; This fixture supplies the complete inventory;
                              ;; asset preflight has its own missing/release tests.
                              middleware/ensure-production-assets! (constantly nil)
                              manifest/read-prepared! (fn [settings]
                                                        (swap! calls conj settings)
                                                        (inventory "prepared"))
                              middleware/get-assets unexpected-work
                              middleware/get-bundled-assets unexpected-work
                              middleware/optimize-all unexpected-work]
                  (middleware/wrap-optimus (constantly {:status 404})))]
    (is (= [(middleware/asset-build-settings)] @calls))
    (with-redefs [manifest/read-prepared! unexpected-work
                  middleware/get-assets unexpected-work
                  middleware/get-bundled-assets unexpected-work
                  middleware/optimize-all unexpected-work]
      (dotimes [_ 3]
        (is (= "prepared" (:body (handler request)))))
      (is (= 404 (:status (handler (assoc request :request-method :post)))))
      (is (= 1 (count @calls))))))

(deftest development-refreshes-declared-bundles-without-a-watcher
  (let [calls (atom 0)
        contents (atom "before")]
    (with-redefs [config/env {:dev true}
                  middleware/get-bundled-assets (fn []
                                                  (swap! calls inc)
                                                  (inventory @contents))
                  middleware/get-assets unexpected-work
                  middleware/optimize-all unexpected-work
                  manifest/read-prepared! unexpected-work]
      (let [handler (middleware/wrap-optimus (constantly {:status 404}))]
        (is (zero? @calls) "Development loads lazily on the first request")
        (is (= "before" (:body (handler request))))
        (is (= 1 @calls))
        (reset! contents "after")
        (is (= "before" (:body (handler request))) "A cached revision is reused")
        ;; Exercise the real two-second TTL instead of replacing its cache owner.
        (Thread/sleep 2100)
        (is (= "after" (:body (handler request))))
        (is (= 2 @calls))))))
