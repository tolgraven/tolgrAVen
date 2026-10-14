(ns website.comment-routes-test
  (:require [clojure.test :refer [deftest is]]
            [website.page :as page]
            [website.page-router :as router]))

(deftest comment-paths-are-complete-typed-routes
  (let [focused (router/request-match "/blog/post/24/comment/113" {})
        standalone (router/request-match "/blog/comment/113" {})]
    (is (= :blog-post-comment (get-in focused [:data :name])))
    (is (= :blog-post (get-in focused [:data :page-route])))
    (is (= {:post-id 24, :comment-id "113"} (page/selection focused)))
    (is (= "blog-comment-113" (page/document-target focused)))
    (is (= :comment (get-in standalone [:data :page])))
    (is (= {:standalone-comment? true, :comment-id "113"} (page/selection standalone)))
    (is (= "standalone-comment" (page/document-target standalone)))
    (is (nil? (router/match "/blog/post/24/comment/113/unknown")))
    (is (thrown? Exception (router/request-match "/blog/comment/invalid.name" {})))))
