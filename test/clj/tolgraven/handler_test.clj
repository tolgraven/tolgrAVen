(ns tolgraven.handler-test
  (:require
    [clojure.test :refer :all]
    [ring.mock.request :refer :all]
    [tolgraven.handler :refer :all]
    [tolgraven.config :as config]
    [tolgraven.middleware :as middleware]
    [tolgraven.middleware.formats :as formats]
    [muuntaja.core :as m]
    [mount.core :as mount]))

(defn parse-json [body]
  (m/decode formats/instance "application/json" body))

(use-fixtures
  :once
  (fn [f]
    (mount/start #'tolgraven.config/env
                 #'tolgraven.handler/app-routes)
    (try
      ;; Route tests do not need to transform every media asset or redirect to TLS.
      (with-redefs [config/env (assoc config/env :test true)
                    middleware/wrap-optimus identity]
        (f))
      (finally (mount/stop #'tolgraven.handler/app-routes
                           #'tolgraven.config/env)))))

(deftest test-app
  (testing "main route"
    (let [response ((app) (request :get "/"))]
      (is (= 200 (:status response)))))

  (testing "not-found route"
    (let [response ((app) (request :get "/invalid"))]
      (is (= 404 (:status response)))
      (is (string? (:body response)))))
  
  (testing "split routes support direct browser navigation"
    (doseq [path ["/cv" "/docs" "/docs/codox/index" "/blog" "/blog/page/2"
                  "/blog/new-post" "/test/search" "/not-found" "/client-oauth/twitter"]]
      (let [response ((app) (request :get path))]
        (is (= 200 (:status response)) path)
        (is (string? (:body response)) path))))

  (testing "services"

    (testing "success"
      (let [response (app-routes (-> (request :post "/api/math/plus")
                                (json-body {:x 10, :y 6})))]
        (is (= 200 (:status response)))
        (is (= {:total 16} (m/decode-response-body response)))))

    (testing "parameter coercion error"
      (let [response (app-routes (-> (request :post "/api/math/plus")
                                (json-body {:x 10, :y "invalid"})))]
        (is (= 400 (:status response)))))

    (testing "response coercion error"
      (let [response (app-routes (-> (request :post "/api/math/plus")
                                (json-body {:x -10, :y 6})))]
        (is (= 500 (:status response)))))

    (testing "content negotiation"
      (let [response (app-routes (-> (request :post "/api/math/plus")
                                (body (pr-str {:x 10, :y 6}))
                                (content-type "application/edn")
                                (header "accept" "application/transit+json")))]
        (is (= 200 (:status response)))
        (is (= {:total 16} (m/decode-response-body response)))))))
