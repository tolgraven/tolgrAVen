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

(defn route-file [uri]
  (let [path (str/replace (java.net.URLDecoder/decode (str/replace uri "+" "%2B") "UTF-8") #"^/+" "")
        avatar (str "storage/v1/object/public/avatars/test/" (apply str (repeat 64 "0")))]
    (when-not (some #{".."} (str/split path #"/"))
      (cond
        (= "" path) (fs/path "resources/public/js/tests/index.html")
        (str/starts-with? path "fixtures/") (fs/path "test/browser" (subs path 9))
        (some #{path} (map #(str avatar "." %) ["png" "webp" "avif"]))
        (fs/path "resources/public/img" (str "tolgrav." (fs/extension path)))
        :else (fs/path (if (str/starts-with? path "js/") "resources/public/js/tests" "resources/public") path)))))

(defn handler [{:keys [uri]}]
  (if-let [file (try (route-file uri) (catch Exception _ nil))]
    (if (fs/regular-file? file)
      {:status 200
       :headers {"content-type" (get mime (fs/extension file) "application/octet-stream")
                 "cache-control" "no-store"}
       :body (fs/file file)}
      {:status 404 :body "Not found"})
    {:status 400 :body "Invalid test path"}))

(defn -main [& args]
  (let [{:keys [port]} (cli/parse-opts args {:spec {:port {:coerce :int :default 4002}}})
        stop! (http/run-server handler {:ip "127.0.0.1" :port port})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(stop!)))
    (println (str "Browser tests: http://127.0.0.1:" port))
    @(promise)))
