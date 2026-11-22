(ns website.media-response-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [optimus.assets :as assets]
            [optimus.link :as link]
            [website.config :as config]
            [website.env :as env]
            [website.fonts.icons :as icons]
            [website.middleware.asset-manifest :as manifest]
            [website.middleware :as middleware]))

(deftest only-successful-production-fingerprinted-modules-are-immutable
  (doseq [[dev? status uri mime cache?]
          [[false 200 "/js/compiled/out/blog.0123456789abcdef0123456789abcdef.js" "text/javascript" true]
           [false 200 "/js/compiled/out/blog.0123456789ABCDEF0123456789ABCDEF.js" "text/javascript" true]
           [false 200 "/js/compiled/out/blog.0123456789abcdef0123456789abcdef.js" "text/html" false]
           [false 404 "/js/compiled/out/blog.0123456789abcdef0123456789abcdef.js" "text/javascript" false]
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

(deftest development-javascript-cannot-retain-a-previous-shadow-instance
  (with-redefs [config/env {:dev true}]
    (doseq [uri ["/js/compiled/out/main.js"
                 "/js/compiled/out/blog.0123456789abcdef0123456789abcdef.js"
                 "/js/compiled/out/cv.js"
                 "/js/compiled/out/cljs-runtime/shadow.cljs.devtools.client.env.js"
                 "/js/document/head.js"
                 "/js/return/return-worker.js"]
            status [200 304]]
      (let [original {:status status
                      :headers {"Content-Type" "text/javascript; charset=utf-8"
                                "Last-Modified" "Wed, 21 Oct 2015 07:28:00 GMT"}
                      :body "worker identity"}
            response ((middleware/wrap-module-cache (constantly original)) {:uri uri})]
        (is (= "no-store" (get-in response [:headers "Cache-Control"])))
        (is (= original (update response :headers dissoc "Cache-Control")))))))

(deftest development-script-policy-keeps-other-response-owners
  (with-redefs [config/env {:dev true}]
    (doseq [[uri mime] [["/api/content" "application/json"]
                        ["/js/compiled/out/missing.js" "text/html"]
                        ["/vendor/library.js" "text/javascript"]]]
      (let [response {:status 200, :headers {"Content-Type" mime}, :body "unchanged"}]
        (is (= response ((middleware/wrap-module-cache (constantly response)) {:uri uri})))))))

(deftest optimized-image-responses
  (testing "production asset middleware preserves image bytes and MIME types"
    ;; Stage permits local HTTP while retaining frozen, optimized assets.
    (with-redefs [config/env {:stage true}
                  env/defaults {:middleware identity}
                  manifest/read-prepared!
                  (fn [_] (assets/load-assets "public" ["/img/tolgrav.avif"
                                                       "/img/tolgrav.webp"]))]
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

(deftest optimized-glyph-styles-retain-icon-selectors
  (with-redefs [config/env {:stage true}]
    (let [files (middleware/optimize-all
                  (assets/load-bundle "public" "icons.css" ["/css/website/icons.min.css"]) {})
          request {:optimus-assets files}
          path (first (link/bundle-paths request ["icons.css"]))
          css (:contents (assets/get-asset-by-path request path))]
      (is (re-matches #"/bundles/[a-f0-9]{12}/icons\.css" path))
      (doseq [glyph [".fa-github:before" ".fa-spinner:before"]]
        (is (string/includes? css glyph)))
      (is (not (string/includes? css "@font-face"))))))

(deftest prepared-font-styles-reference-the-served-woff2-bytes
  (with-redefs [icons/*prepared (atom {:assets {}})]
    (let [{:keys [css]} (icons/prepare!)
          fonts (re-seq #"url\(\"(/webfonts/icons/(fa-(?:brands-400|solid-900))-([a-f0-9]{64})\.woff2)\"\)" css)
          handler (icons/wrap-assets (constantly {:status 404}))]
      (is (= #{"fa-brands-400" "fa-solid-900"} (set (map #(nth % 2) fonts))))
      (is (= 2 (count (re-seq #"unicode-range:U\+" css))))
      (doseq [[_ url _ digest] fonts]
        (let [{:keys [status headers body]} (handler {:uri url :request-method :get})]
          (is (= 200 status))
          (is (= "font/woff2" (get headers "Content-Type")))
          (is (= "public, max-age=31536000, immutable" (get headers "Cache-Control")))
          (with-open [input (io/input-stream body)]
            (let [bytes (.readAllBytes input)]
              (is (= "wOF2" (String. bytes 0 4 "US-ASCII")))
              (is (= digest (manifest/digest bytes)))
              (is (= (str (alength bytes)) (get headers "Content-Length"))))))))))

(deftest immutable-media-filenames-cache-whole-and-range-responses
  (doseq [status [200 206]
          [uri mime] [["/img/photo-v2.avif" "image/avif"]
                      ["/img/logo.svg" "image/svg+xml"]
                      ["/media/demo-v3.webm" "video/webm"]]]
    (with-redefs [config/env {:stage true}]
      (let [response {:status status
                      :headers {"Content-Type" mime, "Content-Range" "bytes 0-9/100"}
                      :body "original bytes"}
            handler (middleware/wrap-media-cache (constantly response))
            actual (handler {:uri uri})]
        (is (= "public, max-age=31536000, immutable" (get-in actual [:headers "Cache-Control"])))
        (is (= response (update actual :headers dissoc "Cache-Control")))))))

(deftest immutable-media-cache-respects-errors-development-and-existing-policy
  (doseq [[dev? uri status mime] [[true "/img/photo.avif" 200 "image/avif"]
                                 [false "/img/missing.avif" 404 "image/avif"]
                                 [false "/media/error.webm" 500 "video/webm"]
                                 [false "/api/image" 200 "image/png"]
                                 [false "/img/error.png" 200 "text/html"]]]
    (with-redefs [config/env {:dev dev?}]
      (let [response {:status status, :headers {"Content-Type" mime}, :body "same"}]
        (is (= response ((middleware/wrap-media-cache (constantly response)) {:uri uri}))))))
  (with-redefs [config/env {:stage true}]
    (doseq [headers [{"Content-Type" "image/avif", "Cache-Control" "max-age=315360000"}
                     {"content-type" "video/webm", "cache-control" "private, no-store"}]]
      (let [response {:status 200, :headers headers, :body "same"}]
        (is (= response ((middleware/wrap-media-cache (constantly response)) {:uri "/media/versioned.avif"})))))))
