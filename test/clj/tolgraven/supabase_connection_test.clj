(ns tolgraven.supabase-connection-test
  (:require [clojure.test :refer [deftest is]]
            [clj-http.conn-mgr :as connections]
            [tolgraven.concurrent :as concurrent]
            [tolgraven.config :as config]
            [tolgraven.platform.supabase :as platform])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [org.apache.http.impl.conn PoolingHttpClientConnectionManager]))

(defn- with-endpoint [f]
  (let [*requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/rest/v1/blog_posts"
      (reify HttpHandler
        (handle [_ exchange]
          (swap! *requests conj
                 {:port (.getPort (.getRemoteAddress exchange))
                  :key (.getFirst (.getRequestHeaders exchange) "apikey")
                  :authorization (.getFirst (.getRequestHeaders exchange) "Authorization")})
          (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
          (.sendResponseHeaders exchange 200 2)
          (with-open [out (.getResponseBody exchange)] (.write out (.getBytes "[]" "UTF-8"))))))
    (.start server)
    (try
      (with-redefs [config/env {:supabase-public-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                               :supabase-service-key "test-key"
                               :supabase-insecure-tls false}]
        (f *requests))
      (finally (.stop server 0)))))

(defn- available [pool]
  (.getAvailable (.getTotalStats ^PoolingHttpClientConnectionManager pool)))

(deftest rest-requests-use-consolidated-credentials
  (with-endpoint
    (fn [*requests]
      (is (= [] (:body (platform/request! :get "blog_posts" {}))))
      (is (= [{:key "test-key", :authorization "Bearer test-key"}]
             (mapv #(select-keys % [:key :authorization]) @*requests))))))

(deftest joined-reads-reuse-connections-with-fresh-headers-and-close-the-pool
  (with-endpoint
    (fn [*requests]
      (let [*pool (atom nil)
            *key (atom "first-test-key")]
        (with-redefs [platform/service-key #(deref *key)]
          (platform/with-rest-connections!
            (fn []
              (reset! *pool (:connection-manager (platform/base-http-opts)))
              (doseq [key ["first-test-key" "second-test-key" "third-test-key"]]
                (reset! *key key)
                (is (= [] (:body (concurrent/await!
                                  (concurrent/submit! #(platform/request! :get "blog_posts" {})))))))
              (platform/with-rest-connections!
                #(is (identical? @*pool (:connection-manager (platform/base-http-opts)))))
              (is (= 1 (available @*pool))))))
        (is (= 1 (count (set (map :port @*requests)))) "The same live TCP connection served all reads")
        (is (= ["first-test-key" "second-test-key" "third-test-key"] (mapv :key @*requests)))
        (is (nil? (:connection-manager (platform/base-http-opts))))
        (is (= 0 (available @*pool)) "The snapshot pool is closed after success")))))

(deftest failed-read-stages-close-their-pool-and-release-the-binding
  (with-endpoint
    (fn [_]
      (let [*pool (atom nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Read stage failed"
              (platform/with-rest-connections!
                (fn []
                  (reset! *pool (:connection-manager (platform/base-http-opts)))
                  (platform/request! :get "blog_posts" {})
                  (throw (ex-info "Read stage failed" {}))))))
        (is (= 0 (available @*pool)))
        (is (nil? (:connection-manager (platform/base-http-opts))))))))

(deftest connection-manager-preserves-explicit-rest-tls-policy
  (let [create connections/make-reusable-conn-manager
        *options (atom nil)]
    (doseq [insecure? [false true]]
      (with-redefs [platform/insecure-rest? (constantly insecure?)
                    connections/make-reusable-conn-manager
                    (fn [options] (reset! *options options) (create options))]
        (platform/with-rest-connections! (fn [] nil))
        (is (= insecure? (:insecure? @*options)))
        (is (= 8 (:threads @*options)))
        (is (= 8 (:default-per-route @*options)))))))
