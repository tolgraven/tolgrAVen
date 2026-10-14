(ns website.home-routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [website.layout :as layout]
            [website.page-router :as pages]
            [website.routes.home :as home]))

(deftest selected-native-pages-reach-the-cold-document-handler
  (with-redefs [layout/render-home (fn [request]
                                    {:status 200
                                     :body {:uri (:uri request)
                                            :parameters (:parameters request)}})
                layout/error-page (fn [error]
                                    {:status (:status error)
                                     :body (:title error)})]
    (let [handler (ring/ring-handler
                    (ring/router [(home/home-routes)
                                  ["/api/route-probe" {:get (constantly {:status 200, :body :api})}]])
                    (ring/create-default-handler))
          request (fn [path]
                    (handler {:uri path, :request-method :get, :query-params {}}))
          sha "c1f44d3a3a5245b9c60240df202afb2ef9daf93b"]
      (testing "cold component paths use the same selected native pages as SPA navigation"
        (doseq [path ["/section/services" (str "/github/commits/" sha)
                      "/blog/post/24/comment/113" "/blog/comment/113" "/neovim"
                      "/" "/about" "/services" "/hire" "/cv" "/docs"
                      "/docs/codox/index" "/blog" "/blog/page/2" "/blog/new-post"]]
          (let [response (request path)]
            (is (some? (pages/match path)) path)
            (is (= 200 (:status response)) path)
            (is (= (:parameters (pages/request-match path {}))
                   (get-in response [:body :parameters])) path)))
        (is (= sha (get-in (request (str "/github/commits/" sha)) [:body :parameters :path :sha]))))
      (testing "legacy document routes coexist with selected native endpoints"
        (doseq [path ["/log" "/log/trace" "/test" "/test/search" "/site/example"
                      "/not-found" "/client-oauth/twitter"]]
          (is (= 200 (:status (request path))) path)))
      (testing "typed input is rejected before rendering"
        (is (= 400 (:status (request "/github/commits/not-a-sha")))))
      (testing "complete native matching leaves unknown tails and other surfaces alone"
        (doseq [path ["/section/unknown" "/section/services/unknown"
                      (str "/github/commits/" sha "/unknown")
                      "/github/unknown" "/blog/post/24/comment/113/unknown"
                      "/neovim/unknown" "/docs/codox/index/unknown" "/about/unknown"
                      "/api/unknown" "/css/website/missing.css"]]
          (is (= 404 (:status (request path))) path))
        (is (= :api (:body (request "/api/route-probe"))))))))
