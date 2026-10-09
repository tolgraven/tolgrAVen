(ns tolgraven.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [optimus.html :as ohtml]
            [ring.core.protocols :as protocols]
            [optimus.link :as olink]
    [tolgraven.config :as config]
    [tolgraven.content.service :as content]
            [tolgraven.ssr :as ssr]
            [tolgraven.layout :as layout]))

(deftest page-heading-image-is-preloaded-before-the-body
  (with-redefs [config/env {:dev true :ssr {:enabled false}}
                content/immediate-content (constantly {:blog {:heading {:bg {:src "img/blog.jpg"}}}})
                ohtml/link-to-js-bundles (fn [& _] nil)]
    (let [body (:body (layout/render-home {:uri "/blog/post/17"}))]
      (is (< (.indexOf body "img/blog.avif") (.indexOf body "<body")))
      (is (.contains (first (filter #(.contains % "img/blog.avif")
                                    (re-seq #"<link[^>]+>" body)))
                     "fetchpriority=\"high\""))
      (is (not (.contains body "img/blog.webp")))
      (is (not (.contains body "img/blog.jpg"))))))

(deftest ssr-failure-status
  (doseq [[error status] [[(ex-info "Missing document" {:status 404}) 404]
                         [(ex-info "Renderer unavailable" {}) 503]]]
    (with-redefs [config/env {:dev true :ssr {:streaming false}}
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
                                          "main.js" ["/bundles/main.hash.js"]
                                          []))
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
                                      "styles.css" ["/bundles/styles.hash.css"]
                                      []))
                ohtml/link-to-js-bundles (fn [& _] [:script {:src "/bundles/main.hash.js"}])]
    (let [{:keys [body headers]} (layout/render-home {:uri "/"})]
      (is (= "</bundles/main.hash.js>; rel=preload; as=script" (get headers "Link")))
      (is (re-find #"<link[^>]*href=\"/bundles/main.hash.js\"[^>]*rel=\"preload\"" body))
      (is (< (.indexOf body "/bundles/main.hash.js") (.indexOf body "<body")))
      (is (re-find #"<script[^>]*defer[^>]*src=\"/bundles/main.hash.js\"" body))
      (is (re-find #"<script[^>]*defer[^>]*src=\"/vendor/supabase.js\"" body))
      (is (< (.indexOf body "src=\"/vendor/supabase.js\"")
             (.indexOf body "src=\"/bundles/main.hash.js\"")))
      (is (.contains body "name=\"analytics-id\""))
      (is (.contains body "window.dataLayer = window.dataLayer || []"))
      (is (not (.contains body "googletagmanager.com")))
      (is (not (.contains body "google-analytics.com")))
      (is (not (.contains body "smoothscroll-polyfill"))))))

(deftest shell-flushes-before-data-completes
  (let [pending (java.util.concurrent.CompletableFuture.)]
    (with-redefs [config/env {:dev true}
                  ssr/enabled? (constantly true)
                  ssr/cached? (constantly false)
                  ssr/page-async! (fn [& _] pending)
                  ssr/shell! (fn [& _] {:html "<h1>Ready CMS heading</h1><p>Skeleton</p>"
                                       :snapshot {:content {}}})
                  ohtml/link-to-js-bundles (fn [& _] nil)]
      (let [response (layout/render-home {:uri "/blog/post/test-28"})
            input (java.io.PipedInputStream. 65536)
            output (java.io.PipedOutputStream. input)
            writer (future (protocols/write-body-to-stream (:body response) response output))
            buffer (byte-array 65536)
            size (.read ^java.io.InputStream input buffer)
            first-chunk (String. buffer 0 size "UTF-8")]
        (is (not (.isDone pending)))
        (is (.contains first-chunk "Ready CMS heading"))
        (is (.contains first-chunk "id=\"ssr-shell\""))
        (is (not (.contains first-chunk "id=\"ssr-bootstrap\"")))
        (.complete pending {:html "<article>Completed page</article>"
                            :snapshot {:posts [] :content {}}})
        (let [rest (slurp input)]
          (is (.contains rest "<article>Completed page</article>"))
          (is (.endsWith rest "</body></html>"))
          (is (= 1 (count (re-seq #"<html(?: |>)" (str first-chunk rest)))))
          (is (not (.contains (str first-chunk rest) "<!--SSR-APP-->")))
          (is (.contains rest "id=\"ssr-bootstrap\""))
          (is (< (.indexOf rest "Completed page") (.indexOf rest "id=\"ssr-complete\""))))
        (is (= "no" (get-in response [:headers "X-Accel-Buffering"])))))))

(deftest cached-ssr-skips-the-shell-entirely
  (with-redefs [config/env {:dev true}
                ssr/enabled? (constantly true)
                ssr/cached? (constantly true)
                ssr/shell! (fn [& _] (throw (ex-info "Cache hit must not build a skeleton" {})))
                ssr/page! (fn [& _] {:html "<main data-stream-enter=\"true\">Cached page</main>"
                                     :snapshot {:posts [] :content {}}})
                ohtml/link-to-js-bundles (fn [& _] nil)]
    (let [body (:body (layout/render-home {:uri "/blog/post/28"}))]
      (is (string? body))
      (is (.contains body "Cached page"))
      (is (not (.contains body "id=\"ssr-shell\"")))
      (is (.contains body "data-hydrate=\"true\"")))))

(deftest preload-hydration-dependencies-in-the-first-response-head
  (with-redefs [config/env {:dev true :ssr {:streaming false}}
                ssr/page! (fn [& _] {:html "<article>Ready</article>" :snapshot {:content {} :posts []}})
                layout/hydration-script-paths (constantly ["/js/compiled/out/link-preview.js"
                                                         "/js/compiled/out/user.js"
                                                         "/js/compiled/out/blog.js"])
                olink/bundle-paths (fn [_ bundles] (when (= ["main.js"] bundles) ["/bundles/main.hash.js"]))
                ohtml/link-to-js-bundles (fn [& _] nil)]
    (let [{:keys [headers body]} (layout/render-home {:uri "/blog/post/28"})]
      (doseq [module ["link-preview" "user" "blog"]]
        (let [path (str "/js/compiled/out/" module ".js")]
          (is (.contains (get headers "Link") path))
          (is (< (.indexOf body path) (.indexOf body "<body"))))))))

(deftest production-inlines-optimized-route-sheets-with-a-bounded-budget
  (let [asset (fn [module css]
                {:path (str "/bundles/hash/module-" module ".css")
                 :bundle (str "module-" module ".css")
                 :contents css})
        blog-css ".blog-post{color:red}/* </style> */"
        cv-css (str ".cv{" (apply str (repeat 16385 " ")) "}")
        request {:optimus-assets [(asset "blog" blog-css)
                                  (asset "cv" cv-css)
                                  (asset "user" ".user-avatar{width:3rem}")
                                  (asset "link-preview" ".link-preview{color:blue}")
                                  (asset "markdown" ".md-rendered{color:green}")]}
        render #(-> request (assoc :uri %) layout/render-home :body)]
    (with-redefs [config/env {:dev false :ssr {:enabled false}}
                  ohtml/link-to-js-bundles (fn [& _] nil)]
      (let [blog (render "/blog")
            cv (render "/cv")]
        (is (.contains blog "data-module-style=\"/bundles/hash/module-blog.css\""))
        (is (.contains blog ".blog-post{color:red}"))
        (is (.contains blog "<\\/style>") "CSS cannot terminate the style element")
        (is (not (re-find #"<link[^>]*module-(blog|user|link-preview|markdown)\.css" blog)))
        (is (< (.indexOf blog "data-module-style") (.indexOf blog "<body")))
        (is (not (.contains blog cv-css)) "Other modules remain lazy")
        (is (re-find #"<link[^>]*module-cv\.css" cv) "Large sheets keep cacheable links")
        (is (.contains cv "module-blog.css") "All URLs remain available to the loader")))))

(deftest route-css-is-in-the-initial-head-and-lazy-css-stays-in-the-manifest
  (with-redefs [config/env {:dev true :ssr {:enabled false}}
                ohtml/link-to-js-bundles (fn [& _] nil)]
    (doseq [[uri present absent] [["/" "user" "blog"] ["/blog" "blog" "cv"] ["/cv" "cv" "blog"]]]
      (let [body (:body (layout/render-home {:uri uri}))
            links (re-seq #"<link[^>]+>" body)]
        (is (some #(.contains % (str "modules/" present ".min.css")) links))
        (is (not-any? #(.contains % (str "modules/" absent ".min.css")) links))
        (is (< (.indexOf body (str "modules/" present ".min.css")) (.indexOf body "<body")))
        (is (.contains body "id=\"module-styles\""))
        (is (= [(str "/css/tolgraven/modules/" absent ".min.css")]
               (get (json/read-str
                      (second (re-find #"<script[^>]*id=\"module-styles\"[^>]*>(.*?)</script>" body))) absent)))))))
