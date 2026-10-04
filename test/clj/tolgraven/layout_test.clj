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
                                        (is (= ["styles.css"] bundles))
                                        [stylesheet])
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
