(ns tolgraven.core-test
  (:require [cljs.test :refer-macros [is are deftest testing]]
            [pjstadig.humane-test-output]
            [tolgraven.components.iframe :as iframe]
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
