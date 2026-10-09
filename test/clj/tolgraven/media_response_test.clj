(ns tolgraven.media-response-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [optimus.assets :as assets]
            [optimus.link :as link]
            [tolgraven.config :as config]
            [tolgraven.env :as env]
            [tolgraven.middleware :as middleware]))

(deftest only-successful-production-fingerprinted-modules-are-immutable
  (doseq [[dev? status uri mime cache?]
          [[false 200 "/js/compiled/out/blog.0123456789abcdef0123456789abcdef.js" "text/javascript" true]
           [false 200 "/js/compiled/out/blog.0123456789ABCDEF0123456789ABCDEF.js" "text/javascript" true]
           [false 200 "/js/compiled/out/blog.0123456789abcdef0123456789abcdef.js" "text/html" false]
           [false 404 "/js/compiled/out/blog.0123456789abcdef0123456789abcdef.js" "text/javascript" false]
           [true 200 "/js/compiled/out/blog.0123456789abcdef0123456789abcdef.js" "text/javascript" false]
           [false 200 "/js/compiled/out/blog.js" "text/javascript" false]
           [false 200 "/api/content/bootstrap" "application/json" false]]]
    (with-redefs [config/env {:dev dev?}]
      (let [handler (middleware/wrap-module-cache
                      (constantly {:status status
                                   :headers {"Content-Type" mime}
                                   :body "content"}))
            response (handler {:uri uri})]
        (is (= (when cache? "public, max-age=31536000, immutable")
               (get-in response [:headers "Cache-Control"])))))))

(deftest optimized-image-responses
  (testing "production asset middleware preserves image bytes and MIME types"
    ;; Stage permits local HTTP while retaining frozen, optimized assets.
    (with-redefs [config/env {:stage true}
                  env/defaults {:middleware identity}
                  middleware/get-assets
                  #(assets/load-assets "public" ["/img/tolgrav.avif"
                                                "/img/tolgrav.webp"])]
      (let [handler (middleware/wrap-base
                     (constantly {:status 404
                                  :headers {"Content-Type" "text/plain"}
                                  :body "Not found"}))]
        (doseq [[path mime-type] [["/img/tolgrav.avif" "image/avif"]
                                 ["/img/tolgrav.webp" "image/webp"]]]
          (let [response (handler {:request-method :get
                                   :uri path
                                   :headers {"sec-fetch-dest" "image"}})]
            (is (= 200 (:status response)))
            (is (= mime-type (get-in response [:headers "Content-Type"])))
            (with-open [actual (io/input-stream (:body response))
                        expected (io/input-stream (io/resource (str "public" path)))]
              (is (= (vec (.readAllBytes expected))
                     (vec (.readAllBytes actual)))))))))))

(deftest icon-font-bundle-rewrites-and-caches-its-fonts
  (with-redefs [config/env {:stage true}]
    (let [files (middleware/optimize-all
                 (assets/load-bundle "public" "icons.css"
                   ["/css/tolgraven/icons.min.css"]) {})
          request {:optimus-assets files}
          css-path (first (link/bundle-paths request ["icons.css"]))
          css (:contents (assets/get-asset-by-path request css-path))]
      (is (re-matches #"/bundles/[a-f0-9]{12}/icons\.css" css-path))
      (doseq [name ["fa-solid-900-core" "fa-brands-400-core"
                   "fa-solid-900" "fa-brands-400"]]
        (let [original (str "/webfonts/" name ".woff2")
              path (link/file-path request original)
              optimized (assets/get-asset-by-path request path)
              plain (first (filter #(and (= original (:path %)) (:outdated %)) files))]
          (is (not= original path))
          (is (.contains css path))
          (is (= "max-age=315360000" (get-in optimized [:headers "Cache-Control"])))
          (is (nil? (get-in plain [:headers "Cache-Control"])))
          (with-open [actual (io/input-stream (assets/get-contents optimized))
                      expected (io/input-stream (io/resource (str "public" original)))]
            (is (= (vec (.readAllBytes expected)) (vec (.readAllBytes actual)))))))
      (is (.contains css "unicode-range:")))))
