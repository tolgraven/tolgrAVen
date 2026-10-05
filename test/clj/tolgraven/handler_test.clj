(ns tolgraven.handler-test
  (:require
    [clojure.test :refer :all]
    [ring.mock.request :refer :all]
    [tolgraven.handler :refer :all]
    [tolgraven.config :as config]
    [tolgraven.middleware :as middleware]
    [tolgraven.middleware.formats :as formats]
    [tolgraven.routes.services :as services]
    [reitit.ring :as ring]
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
      (with-redefs [config/env (assoc config/env :test true :ssr {:enabled false})
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

(deftest malli-request-boundaries
  (testing "query numbers reach the handler as numbers"
    (let [response (app-routes (request :get "/api/math/plus?x=3&y=4"))]
      (is (= 200 (:status response)))
      (is (= {:total 7} (m/decode-response-body response)))))
  (testing "request checks remain on with internal checks disabled"
    (with-redefs [config/validation-enabled? (constantly false)]
      (let [handler (ring/ring-handler (ring/router [(services/service-routes)]))
            response (handler (request :get "/api/math/plus?x=3&y=private-value"))
            body (m/decode-response-body response)]
        (is (= 400 (:status response)))
        (is (= ["y"] (get-in body [:issues 0 :path])))
        (is (not (.contains (pr-str body) "private-value")))
        (is (= 200 (:status (handler (request :get "/api/math/plus?x=-3&y=1"))))
            "Response checks, unlike input checks, follow the disabled setting"))))
  (testing "page paths reject invalid parameters before SSR"
    (let [response ((app) (request :get "/blog/page/zero"))]
      (is (= 400 (:status response)))
      (is (.contains (:body response) "Invalid page address"))
      (is (.contains (:body response) "[:nr]"))))
  (testing "unsafe documentation names never reach resource lookup"
    (is (= 400 (:status (app-routes (request :get "/api/doc?path=../secret")))))))
