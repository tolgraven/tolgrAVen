(ns tolgraven.core-test
  (:require [cljs.test :refer-macros [is are deftest testing use-fixtures]]
            [pjstadig.humane-test-output]
            [reagent.core :as reagent :refer [atom]]
            [tolgraven.components.iframe-popover :as iframe-popover]
            [tolgraven.core :as tc]))

(deftest test-home
  (is (= true true)))

(deftest external-http-url-test
  (testing "only cross-origin HTTP(S) links are previewable"
    (are [expected href]
      (= expected
         (iframe-popover/external-http-url?
           href
           "https://tolgraven.se/blog"))
      true "https://example.com/article"
      true "http://example.com/article"
      false "/about"
      false "https://tolgraven.se/about"
      false "#main"
      false "mailto:hello@example.com")))
