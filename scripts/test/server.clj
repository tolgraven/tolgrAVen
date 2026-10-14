(ns test.server
  "Local static browser suite server. Live integration uses the flush-aware proxy."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def mime
  {"html" "text/html; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "json" "application/json"
   "css" "text/css"
   "png" "image/png"
   "webp" "image/webp"
   "avif" "image/avif"
   "svg" "image/svg+xml"
   "woff2" "font/woff2"})

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
          (str/starts-with? path "fixtures/") (fs/path "test/browser" (subs path 9))
          (some #{path} (map #(str avatar "." %) ["png" "webp" "avif"]))
          (fs/path "resources/public/img" (str "tolgrav." (fs/extension path)))
          :else (fs/path (if (str/starts-with? path "js/") "resources/public/js/tests" "resources/public") path))))))

(defn handler
  ([request] (handler nil request))
  ([glass-output {:keys [uri]}]
    (if-let [file (try (route-file uri glass-output) (catch Exception _ nil))]
      (if (fs/regular-file? file)
        {:status 200
         :headers {"content-type" (get mime (fs/extension file) "application/octet-stream")
                   "cache-control" "no-store"}
         :body (if (and glass-output (= uri "/tests/"))
                 (str/replace (slurp (fs/file file)) "<head>"
                              "<head><link rel=\"stylesheet\" href=\"/css/website/main.css\">")
                 (fs/file file))}
        {:status 404 :body "Not found"})
      {:status 400 :body "Invalid test path"})))

(defn -main [& args]
  (let [{:keys [port glass-output]} (cli/parse-opts args {:spec {:port {:coerce :int :default 4002}
                                                        :glass-output {:coerce :string}}})
        stop! (http/run-server (partial handler glass-output) {:ip "127.0.0.1" :port port})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(stop!)))
    (println (str "Browser tests: http://127.0.0.1:" port))
    @(promise)))
