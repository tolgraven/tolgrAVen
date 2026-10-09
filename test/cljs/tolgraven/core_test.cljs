(ns tolgraven.core-test
  (:require [cljs.test :refer-macros [is are deftest testing]]
            [tolgraven.components.iframe :as iframe]
            [tolgraven.events :as events]
            [tolgraven.modules.link-preview.util :as link-preview]
            [tolgraven.navigation.routes :as routes]))

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
            "https://third.example/c" "https://fourth.example/foo_(bar)"
            "https://fifth.example/?a=1&amp;b=2"]
           (link-preview/external-urls
             (str "[A](https://example.com/a), https://example.com/a! "
                  "https://other.example/b //third.example/c "
                  "[nested](https://fourth.example/foo_(bar)) "
                  "<https://fifth.example/?a=1&amp;b=2>")
             "https://tolgraven.se/blog"))))
  (testing "same-origin links are excluded before observers exist"
    (is (empty? (link-preview/external-urls
                  "[About](https://tolgraven.se/about) /relative"
                  "https://tolgraven.se/blog")))))

(deftest theme-resolution-and-toggle-test
  (testing "explicit theme always wins"
    (with-redefs [events/system-prefers-dark? (constantly false)]
      (is (= "dark" (events/resolved-theme "dark")))
      (is (= "light" (events/resolved-theme "light")))))
  (testing "missing or invalid selection follows system preference"
    (with-redefs [events/system-prefers-dark? (constantly true)]
      (is (= "dark" (events/resolved-theme nil)))
      (is (= "dark" (events/resolved-theme "unknown"))))
    (with-redefs [events/system-prefers-dark? (constantly false)]
      (is (= "light" (events/resolved-theme nil)))))
  (testing "toggle inverts effective theme"
    (with-redefs [events/system-prefers-dark? (constantly true)]
      (is (= "light" (events/toggled-theme nil)))
      (is (= "light" (events/toggled-theme "dark"))))
    (with-redefs [events/system-prefers-dark? (constantly false)]
      (is (= "dark" (events/toggled-theme nil)))
      (is (= "dark" (events/toggled-theme "light"))))))
