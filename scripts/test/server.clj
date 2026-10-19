(ns test.server
  "Local static browser suite server. Live integration uses the flush-aware proxy."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [org.httpkit.client :as client]
            [org.httpkit.server :as http]
            [website.modules.neovim.policy :as neovim-policy])
  (:import [java.util.zip GZIPInputStream]))

(def mime
  {"html" "text/html; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "json" "application/json"
   "css" "text/css"
   "png" "image/png"
   "webp" "image/webp"
   "avif" "image/avif"
   "svg" "image/svg+xml"
   "woff2" "font/woff2"
   "wasm" "application/wasm"})

(def docs-fixtures
  {"fixtures/docs/index.html" (fs/path "test/fixtures/docs/index.html.gz")
   "fixtures/docs/docs.html" (fs/path "test/fixtures/docs/docs.html.gz")})

(defn route-file
  ([uri] (route-file uri nil))
  ([uri glass-output]
    (let [path (str/replace (java.net.URLDecoder/decode (str/replace uri "+" "%2B") "UTF-8") #"^/+" "")
          avatar (str "storage/v1/object/public/avatars/test/" (apply str (repeat 64 "0")))]
      (when-not (some #{".."} (str/split path #"/"))
        (cond
          (and glass-output (= "" path)) (fs/path "test/browser/liquid-glass.html")
          (and glass-output (= "tests/" path)) (fs/path glass-output "tests/index.html")
          (and glass-output (str/starts-with? path "tests/")) (fs/path glass-output path)
          (and glass-output (str/starts-with? path "js/demo/")) (fs/path glass-output path)
          (and glass-output (str/starts-with? path "js/")) (fs/path glass-output "tests" path)
          (= "" path) (fs/path "resources/public/js/tests/index.html")
          (= path "fixtures/editor-seed.json") (fs/path "resources/content-seed.json")
          (contains? docs-fixtures path) (get docs-fixtures path)
          (= path "fixtures/neovim-unsupported.html") (fs/path "test/browser/neovim-visual.html")
          (str/starts-with? path "js/neovim-check/") (fs/path "resources/public" path)
          (str/starts-with? path "fixtures/") (fs/path "test/browser" (subs path 9))
          (some #{path} (map #(str avatar "." %) ["png" "webp" "avif"]))
          (fs/path "resources/public/img" (str "tolgrav." (fs/extension path)))
          :else (fs/path (if (str/starts-with? path "js/") "resources/public/js/tests" "resources/public") path))))))

(defn handler
  ([request] (handler nil request))
  ([glass-output {:keys [uri]}]
    (if-let [file (try (route-file uri glass-output) (catch Exception _ nil))]
      (if (fs/regular-file? file)
        (let [docs-fixture? (contains? (set (vals docs-fixtures)) file)
              fixture-html (when (and (= "html" (fs/extension file))
                                      (or (= uri "/") (str/starts-with? uri "/fixtures/")))
                             (slurp (fs/file file)))]
          {:status 200
           :headers (merge {"content-type" (if docs-fixture? (get mime "html")
                                              (get mime (fs/extension file) "application/octet-stream"))
                            "cache-control" "no-store"}
                           (if (#{"/fixtures/neovim.html" "/fixtures/neovim-visual.html"} uri)
                             neovim-policy/isolation-headers
                             (neovim-policy/headers-for uri)))
           :body (cond
                   docs-fixture?
                   (with-open [input (GZIPInputStream. (io/input-stream (fs/file file)))]
                     (.readAllBytes input))
                   (and glass-output (= uri "/tests/"))
                   (str/replace (slurp (fs/file file)) "<head>"
                                "<head><link rel=\"stylesheet\" href=\"/css/website/main.css\"><link rel=\"stylesheet\" href=\"/fixtures/icon-fonts.css\">")
                   (and fixture-html (str/includes? fixture-html "</head>"))
                   ;; Static component documents have no backend CMS/SSR head.
                   (str/replace-first fixture-html "</head>"
                                      "<link rel=\"stylesheet\" href=\"/fixtures/icon-fonts.css\"></head>")
                   :else (fs/file file))})
        {:status 404 :body "Not found"})
      {:status 400 :body "Invalid test path"})))

(defn editor-handler [cms-origin handler request]
  (let [uri (:uri request)
        reply (fn [status body]
                {:status status
                 :headers {"content-type" "application/json", "cache-control" "no-store"}
                 :body (json/generate-string body)})]
    (case uri
      "/fixtures/editor-live.json"
      (let [result @(client/get (str cms-origin "/api/site-content")
                     {:headers {"Authorization" (str "Bearer " (System/getenv "EDITOR_FIXTURE_CMS_READ_TOKEN"))}
                      :timeout 15000})
            body (try (json/parse-string (:body result) true) (catch Exception _ {}))]
        (reply (or (:status result) 503) (:content body)))
      "/api/content/editor" (reply 200 {:admin-url (str cms-origin "/admin"), :writable? true})
      ("/api/content/field" "/api/content/cv-entry")
      (let [creation? (= uri "/api/content/cv-entry")
            request! (if creation? client/post client/put)
            result @(request! (str cms-origin (if creation? "/api/site-content/cv-entry" "/api/site-content/field"))
                     {:headers {"Authorization" (str "Bearer " (System/getenv "EDITOR_FIXTURE_CMS_WRITE_TOKEN"))
                                "Content-Type" "application/json"}
                      :body (slurp (:body request))
                      :timeout 15000})
            body (try (json/parse-string (:body result) true) (catch Exception _ {}))]
        (if (= 409 (:status result))
          (reply 409 {:error (if creation? "The timeline changed. Review its current entries, then reopen Add entry."
                              "This field changed in Strapi. Use the latest value before editing again.")
                      :content-bundle body})
          (reply (or (:status result) 503) body)))
      ;; Upload status is a component fixture; it does not claim Storage/codec
      ;; publication. The ordinary upload effect still builds native FormData.
      "/api/supabase/media" (reply 200 {:id "fixture-upload", :status "processing"})
      "/api/supabase/media/fixture-upload"
      (reply 200 {:id "fixture-upload"
                  :status "ready"
                  :asset {:id "fixture-upload"
                          :src "/img/tolgrav.png"
                          :width 320
                          :height 320
                          :renditions {:original {:src "/img/tolgrav.png"
                                                  :width 320
                                                  :height 320
                                                  :bytes 1
                                                  :mime "image/png"}
                                       :webp [{:src "/img/tolgrav.webp", :width 320, :height 320, :bytes 1, :mime "image/webp"}]
                                       :avif [{:src "/img/tolgrav.avif", :width 320, :height 320, :bytes 1, :mime "image/avif"}]}}})
      (handler request))))

(defn -main [& args]
  (let [{:keys [port glass-output editor-cms-origin]} (cli/parse-opts args {:spec {:port {:coerce :int :default 4002}
                                                        :editor-cms-origin {:coerce :string}
                                                        :glass-output {:coerce :string}}})
        _ (when (and editor-cms-origin (not (re-matches #"http://127\.0\.0\.1:[0-9]+" editor-cms-origin)))
            (throw (ex-info "Editor fixtures require a disposable loopback CMS" {})))
        handler' (if editor-cms-origin
                   (partial editor-handler editor-cms-origin (partial handler glass-output))
                   (partial handler glass-output))
        stop! (http/run-server handler' {:ip "127.0.0.1" :port port})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(stop!)))
    (println (str "Browser tests: http://127.0.0.1:" port))
    @(promise)))
