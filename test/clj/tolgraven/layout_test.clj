(ns tolgraven.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [optimus.html :as ohtml]
            [optimus.link :as olink]
            [tolgraven.config :as config]
            [tolgraven.layout :as layout]))

(deftest first-paint-styles
  (doseq [[dev? stylesheet] [[true "css/tolgraven/main.min.css"]
                             [false "/bundles/styles.hash.css"]]]
    (testing (if dev? "development" "production")
      (with-redefs [config/env {:dev dev?}
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
