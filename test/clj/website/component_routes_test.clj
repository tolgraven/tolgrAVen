(ns website.component-routes-test
  (:require [clojure.test :refer [deftest is]]
            [reitit.core :as reitit]
            [website.navigation.component-routes :as components]
            [website.page-router :as pages]
            [website.modules.github.routes :as github]))

(deftest component-paths-preserve-page-ownership-and-exact-matching
  (let [router (reitit/router
                 [(components/mount ["/" {:name :home, :module :home, :page :page}]
                                    github/spec)])
        page (reitit/match-by-path router "/")
        commit (reitit/match-by-path router "/github/commits/abcdef123456")]
    (is (= :home (get-in page [:data :name])))
    (is (= :github-commit (get-in commit [:data :name])))
    (is (= :home (get-in commit [:data :module])))
    (is (= :page (get-in commit [:data :page])))
    (is (= :home (get-in commit [:data :page-route])))
    (is (= :github (get-in commit [:data :component-route :module])))
    (is (nil? (reitit/match-by-path router "/github/commits/abcdef123456/unknown")))
    (is (= [:github/navigate-commit "abcdef123456"]
           (components/action (assoc commit :parameters {:path {:sha "abcdef123456"}}))))))

(deftest nested-component-tails-remain-native-routes
  (let [route (components/mount
                ["/blog/post/:post" {:name :post, :module :blog, :page :post}]
                [["/comment/:comment" {:name :comment-focus}
                  ["/reply/:reply" {:name :reply-focus}]]])
        router (reitit/router [route])]
    (is (= :post (get-in (reitit/match-by-path router "/blog/post/24") [:data :name])))
    (is (= {:post "24", :comment "113", :reply "114"}
           (:path-params (reitit/match-by-path router "/blog/post/24/comment/113/reply/114"))))
    (is (= :blog (get-in (reitit/match-by-path router "/blog/post/24/comment/113") [:data :module])))))

(deftest mounted-component-paths-use-the-real-page-router-and-coercion
  (let [match (pages/request-match "/github/commits/abcdef123456" {})]
    (is (= :home (get-in match [:data :module])))
    (is (= "abcdef123456" (get-in match [:parameters :path :sha])))
    (is (nil? (pages/match "/github/commits/abcdef123456/unknown")))
    (is (thrown? Exception (pages/request-match "/github/commits/invalid" {})))))

(deftest component-tails-retain-the-typed-host-page-identity
  (let [post (pages/request-match "/blog/post/title-24" {})
        comment (pages/request-match "/blog/post/24/comment/113" {})
        other (pages/request-match "/blog/post/25/comment/113" {})]
    (is (components/same-page? post comment)
        "Slug and numeric links name the same typed post")
    (is (components/same-page? comment (pages/request-match "/blog/post/24/comment/114" {})))
    (is (not (components/same-page? comment other)))
    (is (not (components/same-page? comment (pages/request-match "/blog/comment/113" {}))))
    (is (components/same-page? (pages/request-match "/" {})
                              (pages/request-match "/github/commits/abcdef1" {})))))
