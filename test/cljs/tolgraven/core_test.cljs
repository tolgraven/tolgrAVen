(ns tolgraven.core-test
  (:require [cljs.test :refer-macros [is are deftest testing]]
            [pjstadig.humane-test-output]
            [tolgraven.components.iframe :as iframe]
            [tolgraven.components.link-preview :as link-preview]
            [tolgraven.routes :as routes]))

(deftest test-home
  (is (= true true)))

(deftest external-http-url-test
  (testing "only cross-origin HTTP(S) links are previewable"
    (are [expected href]
      (= expected
         (routes/external-http-url?
           href
           "https://tolgraven.se/blog"))
      true "https://example.com/article"
      true "http://example.com/article"
      false "/about"
      false "https://tolgraven.se/about"
      false "#main"
      false "mailto:hello@example.com")))

(deftest iframe-trust-test
  (testing "only trusted first-party authors opt into iframe capabilities"
    (is (= "allow-forms allow-scripts"
           (:trusted iframe/sandbox-by-trust)))
    (is (= "" (:user iframe/sandbox-by-trust)))
    (is (= "" (:untrusted iframe/sandbox-by-trust)))))

(deftest external-url-extraction-test
  (testing "raw markdown and text links are normalized and deduplicated"
    (is (= ["https://example.com/a" "https://other.example/b"
            "https://third.example/c"]
           (link-preview/external-urls
             (str "[A](https://example.com/a), https://example.com/a! "
                  "https://other.example/b //third.example/c")
             "https://tolgraven.se/blog"))))
  (testing "same-origin links are excluded before observers exist"
    (is (empty? (link-preview/external-urls
                  "[About](https://tolgraven.se/about) /relative"
                  "https://tolgraven.se/blog")))))
