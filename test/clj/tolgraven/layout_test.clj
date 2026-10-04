(ns tolgraven.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [optimus.html :as ohtml]
            [optimus.link :as olink]
            [tolgraven.config :as config]
            [tolgraven.ssr :as ssr]
            [tolgraven.layout :as layout]))

(deftest ssr-failure-status
  (doseq [[error status] [[(ex-info "Missing document" {:status 404}) 404]
                         [(ex-info "Renderer unavailable" {}) 503]]]
    (with-redefs [config/env {:dev true}
                  ssr/enabled? (constantly true)
                  ssr/page! (fn [& _] (throw error))
                  ohtml/link-to-js-bundles (fn [& _] nil)]
      (let [response (layout/render-home {:uri "/docs/codox/missing"})]
        (is (= status (:status response)))
        (is (= "no-store" (get-in response [:headers "Cache-Control"])))
        (is (.contains (:body response) "Please retry."))))))

(deftest first-paint-styles
  (doseq [[dev? stylesheet] [[true "css/tolgraven/main.min.css"]
                             [false "/bundles/styles.hash.css"]]]
    (testing (if dev? "development" "production")
      (with-redefs [config/env {:dev dev? :ssr {:enabled false}}
                    olink/bundle-paths (fn [_ bundles]
                                        (case (first bundles)
                                          "styles.css" [stylesheet]
                                          "main.js" ["/bundles/main.hash.js"]))
                    ohtml/link-to-js-bundles (fn [& _] nil)]
        (let [body (:body (layout/render-home {}))
              links (re-seq #"<link[^>]+>" body)
              main-links (filter #(.contains % stylesheet) links)]
          (is (= 1 (count main-links)))
          (is (not (re-find #"media=|onload=" (first main-links))))
          (is (.contains body "name=\"color-scheme\""))
          (is (.contains body "prefers-color-scheme: light"))
          (is (.contains body "prefers-color-scheme: dark"))
          (is (.contains body "href=\"/site.webmanifest\""))
          (is (.contains body "rel=\"apple-touch-icon\"")))))))

(deftest preload-matches-the-executed-bundle
  (with-redefs [config/env {:dev false :ssr {:enabled false}}
                olink/bundle-paths (fn [_ bundles]
                                    (case (first bundles)
                                      "main.js" ["/bundles/main.hash.js"]
                                      "styles.css" ["/bundles/styles.hash.css"]))
                ohtml/link-to-js-bundles (fn [& _] [:script {:src "/bundles/main.hash.js"}])]
    (let [{:keys [body headers]} (layout/render-home {:uri "/"})]
      (is (= "</bundles/main.hash.js>; rel=preload; as=script" (get headers "Link")))
      (is (re-find #"<link[^>]*href=\"/bundles/main.hash.js\"[^>]*rel=\"preload\"" body))
      (is (< (.indexOf body "/bundles/main.hash.js") (.indexOf body "<body")))
      (is (.contains body "<script src=\"/bundles/main.hash.js\"")))))
