(ns audit.middleware
  "Read-only application audit; disposable fixtures are created only in the CPU snapshot."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [optimus.assets :as assets]
            [optimus.optimizations :as optimizations]
            [optimus.optimizations.minify :as minify]
            [optimus.strategies :as strategies]
            [optimus-img-transform.core :as images]
            [ring.core.protocols :as ring]
            [ring.middleware.anti-forgery :as csrf]
            [ring.middleware.multipart-params :as multipart]
            [reitit.ring :as reitit]
            [tolgraven.routes.services :as services]
            [tolgraven.schema.http :as http-schema]
            [tolgraven.config :as config]
            [tolgraven.env :as env]
            [tolgraven.layout :as layout]
            [tolgraven.media.cms :as cms]
            [tolgraven.media.pipeline :as pipeline]
            [tolgraven.media.storage :as storage]
            [tolgraven.middleware :as middleware])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.io ByteArrayOutputStream InputStream]
           [java.util.zip GZIPInputStream]))

(defn now [] (System/nanoTime))
(defn elapsed [start] (/ (- (now) start) 1e6))
(defn measured [label f]
  (let [start (now)
        value (f)]
    {:step label, :milliseconds (elapsed start), :value value}))
(defn body-bytes [body]
  (let [out (ByteArrayOutputStream.)]
    (ring/write-body-to-stream body {} out)
    (.toByteArray out)))
(defn body-summary [response]
  (let [body (:body response)
        raw (body-bytes body)
        decoded (if (= "gzip" (get-in response [:headers "Content-Encoding"]))
                  (with-open [in (GZIPInputStream. (io/input-stream raw))]
                    (.readAllBytes in))
                  raw)]
    {:status (:status response)
     :headers (select-keys (:headers response)
                          ["Content-Type" "Content-Encoding" "Content-Length" "Content-Range"
                           "Cache-Control" "Vary" "Accept-Ranges" "Location" "X-Frame-Options"
                           "X-Content-Type-Options" "Strict-Transport-Security"])
     :body-type (str (type body))
     :wire-bytes (alength raw)
     :decoded-bytes (alength decoded)
     :small-body (when (<= (alength decoded) 30) (String. decoded "UTF-8"))}))

(defn prepare! []
  ;; Fixtures stand in only for unavailable compiled JS. CSS and media are real.
  (doseq [[path contents] [["resources/public/js/compiled/out/main.js"
                            (str "var auditFixture='" (apply str (repeat 1200 "x")) "';")]
                           ["resources/public/js/document/head.js" "window.auditHead=true;"]
                           ["resources/public/js/document/entrance.js"
                            (str "var auditEntrance='" (apply str (repeat 1200 "y")) "';")]]]
    (io/make-parents path)
    (spit path contents)))

(defn pipeline-report []
  (let [loaded (measured "get-assets" #(vec (middleware/get-assets)))
        unique (measured "guard-against-duplicate-assets"
                         #(vec (strategies/guard-against-duplicate-assets (:value loaded))))
        stages [["module-source-maps" middleware/module-source-maps]
                ["minify-js-assets" #(optimizations/minify-js-assets % {:uglify-js {:mangle-names false}})]
                ["inline-css-imports" optimizations/inline-css-imports]
                ["concatenate-bundles" optimizations/concatenate-bundles]
                ["transform-images" #(images/transform-images % {:regexp #"(/media/.*\.jpg)|(/img/.*\.(jpg|png))"
                                                                 :quality 0.80
                                                                 :progressive true})]
                ["add-cache-busted-expires-headers" optimizations/add-cache-busted-expires-headers]
                ["add-last-modified-headers" optimizations/add-last-modified-headers]]
        result (reduce (fn [{:keys [files timings]} [name f]]
                         (let [stage (measured name #(vec (f files)))]
                           {:files (:value stage)
                            :timings (conj timings (dissoc stage :value))}))
                       {:files (:value unique)
                        :timings (mapv #(dissoc % :value) [loaded unique])}
                       stages)
        css (filter #(string/ends-with? (:path %) ".css") (:value unique))
        js (filter #(string/ends-with? (:path %) ".js") (:value unique))]
    {:timings (:timings result)
     :loaded-count (count (:value loaded))
     :unique-count (count (:value unique))
     :optimized-count (count (:files result))
     :css-import-count (reduce + (map #(count (re-seq #"@import\b" (:contents %))) css))
     :css-count (count css)
     :js-count (count js)
     :js-already-minified-count (count (filter #(minify/looks-like-already-minified (:contents %)) js))
     :files (:files result)}))

(def secret "disposable-audit-token")
(defn route [called request]
  (swap! called update (:uri request) (fnil inc 0))
  (case (:uri request)
    "/api/media/strapi/webhook"
    {:status 200, :body (cms/webhook! request)}
    "/api/probe/multipart"
    {:status 200, :body {:fields (vec (keys (:multipart-params request)))}}
    "/probe/token"
    {:status 200, :headers {"Content-Type" "text/plain"}, :body (force csrf/*anti-forgery-token*)}
    "/media/audit-range-v2.webm"
    {:status 200
     :headers {"Content-Type" "video/webm", "Content-Length" "10"}
     :body "0123456789"}
    "/probe/text"
    {:status 200, :headers {"Content-Type" "text/plain"}, :body (apply str (repeat 300 "abc"))}
    {:status 404, :headers {"Content-Type" "text/plain"}, :body "not found"}))

(defn request [uri & [headers method]]
  {:request-method (or method :get)
   :uri uri
   :scheme :http
   :server-name "localhost"
   :server-port 80
   :headers (or headers {})})

(defn audit-handler [called]
  (reitit/ring-handler
    (reitit/router
      [["/api" (second (#'services/ordinary-service-routes))
        ["/media/strapi/webhook" {:post (partial route called)}]
        ["/probe/multipart" {:parameters {:multipart [:map [:file http-schema/upload]]}
                             :post (partial route called)}]]])
    (partial route called)))

(defn ring-report [optimized]
  (let [called (atom {})
        handler (middleware/wrap-base (audit-handler called))
        checks [["text-gzip" "/probe/text" {"accept-encoding" "gzip"}]
                ["gzip-explicit-rejection-after-wildcard" "/probe/text" {"accept-encoding" "*;q=1, gzip;q=0"}]
                ["image-gzip-without-fetch-dest" "/img/tolgrav.avif" {"accept-encoding" "gzip"}]
                ["image-native-dest" "/img/tolgrav.avif" {"accept-encoding" "gzip", "sec-fetch-dest" "image"}]
                ["video-suffix" "/media/audit-range-v2.webm" {"range" "bytes=-3", "sec-fetch-dest" "video"}]
                ["video-unsatisfiable" "/media/audit-range-v2.webm" {"range" "bytes=20-30", "sec-fetch-dest" "video"}]
                ["video-normal" "/media/audit-range-v2.webm" {"range" "bytes=2-5", "sec-fetch-dest" "video"}]
                ["optimus-image-range" "/img/tolgrav.avif" {"range" "bytes=0-9", "sec-fetch-dest" "image"}]
                ["optimus-image-head" "/img/tolgrav.avif" {} :head]
                ["optimus-image-post" "/img/tolgrav.avif" {} :post]]]
    {:responses (into {}
                  (for [[name uri headers method] checks]
                    [name (body-summary (handler (request uri headers method)))]))
     :handler-calls @called} ))

(defn http-report []
  (let [called (atom {})
        parse-calls (atom [])
        original-parse multipart/parse-multipart-params
        handler (middleware/wrap-base (audit-handler called))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        client (HttpClient/newHttpClient)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [headers (into {} (map (fn [[k vs]] [(string/lower-case k) (first vs)])
                                     (.entrySet (.getRequestHeaders exchange))))
                req (assoc (request (.getPath (.getRequestURI exchange)) headers
                                    (keyword (string/lower-case (.getRequestMethod exchange))))
                           :body (.getRequestBody exchange))
                response (with-redefs [multipart/parse-multipart-params
                                      (fn [request & args]
                                        (when (string/starts-with? (get-in request [:headers "content-type"] "") "multipart/")
                                          (swap! parse-calls conj {:already-parsed? (boolean (:multipart-params request))}))
                                        (apply original-parse request args))]
                           (handler req))
                body (body-bytes (:body response))]
            (doseq [[k v] (:headers response)]
              (.add (.getResponseHeaders exchange) k (str v)))
            (.sendResponseHeaders exchange (:status response) (alength body))
            (with-open [out (.getResponseBody exchange)] (.write out body))))))
    (.start server)
    (try
      (let [origin (str "http://127.0.0.1:" (.getPort (.getAddress server)))
            send! (fn [path method headers text]
                    (let [builder (HttpRequest/newBuilder (URI/create (str origin path)))]
                      (doseq [[k v] headers] (.header builder k v))
                      (.method builder method (java.net.http.HttpRequest$BodyPublishers/ofString (or text "")))
                      (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))))
            token (send! "/probe/token" "GET" {} nil)
            cookie (-> token .headers (.firstValue "set-cookie") (.orElse "") (string/split #";" ) first)
            payload "{\"event\":\"media.create\",\"media\":{\"id\":1,\"url\":\"/uploads/a.png\"}}"
            ordinary {"Content-Type" "application/json", "Authorization" (str "Bearer " secret)}
            blocked (send! "/api/media/strapi/webhook" "POST" ordinary payload)
            blocked-calls (get @called "/api/media/strapi/webhook" 0)
            accepted (send! "/api/media/strapi/webhook" "POST"
                            (assoc ordinary "Cookie" cookie "X-Ring-Anti-Forgery" (.body token)) payload)
            uploaded (send! "/api/probe/multipart" "POST"
                            {"Content-Type" "multipart/form-data; boundary=audit-boundary"
                             "X-Ring-Anti-Forgery" "audit"}
                            (str "--audit-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                                 "Content-Type: text/plain\r\n\r\nhello\r\n--audit-boundary--\r\n"))]
        {:webhook-with-bearer-only {:status (.statusCode blocked)
                                   :handler-called? (pos? blocked-calls)}
         :webhook-with-bearer-cookie-and-csrf {:status (.statusCode accepted)
                                             :body (.body accepted)}
         :multipart {:status (.statusCode uploaded)
                     :parse-calls @parse-calls
                     :handler-called? (pos? (get @called "/api/probe/multipart" 0))}
         :handler-calls @called})
      (finally (.stop server 0)))))

(defn -main [& _]
  (when-not (and (.exists (io/file "/.dockerenv"))
                 (= "/workspace" (.getCanonicalPath (io/file "."))))
    (throw (ex-info "Run this fixture audit only through tolgraven-cpu run in its disposable Docker snapshot" {})))
  (prepare!)
  (with-redefs [config/env {:stage true, :media {:strapi-webhook-token secret}}
                env/defaults {:middleware identity}
                layout/browser-modules (constantly {:main {:output-name "main.js"}})
                pipeline/configured! (constantly nil)
                cms/intake! (constantly {:accepted true})
                storage/public-url #(str "https://audit.invalid/storage/" %)
                layout/error-page (fn [{:keys [status]}]
                                    {:status status, :headers {"Content-Type" "text/plain"}, :body "Disposable audit error"})]
    (let [pipeline (pipeline-report)
          ;; Preserve all actual outer middleware and frozen Optimus dispatch, but
          ;; reuse measured optimized files rather than initialize GraalJS twice.
          fixture-assets (:files pipeline)
          _ (do (io/make-parents "target/middleware-audit-pipeline.json")
                (spit "target/middleware-audit-pipeline.json"
                      (json/write-str (dissoc pipeline :files) :indent true)))
          reports (with-redefs [middleware/get-assets (constantly fixture-assets)
                                middleware/optimize-all (fn [files _] files)]
                    {:ring (ring-report fixture-assets)
                     :http (http-report)
                     :production-static (with-redefs [config/env {}]
                                          (let [handler (middleware/wrap-base (constantly {:status 404, :body "missing"}))]
                                            (body-summary (handler (request "/img/tolgrav.avif" {"sec-fetch-dest" "image"})))))} )
          report (merge {:environment {:java (System/getProperty "java.version")
                                      :processors (.availableProcessors (Runtime/getRuntime))
                                      :linux-load-average (.getSystemLoadAverage (java.lang.management.ManagementFactory/getOperatingSystemMXBean))
                                      :max-heap-bytes (.maxMemory (Runtime/getRuntime))
                                      :autorefresh-resource-with-leading-slash? (boolean (io/resource "/js/compiled/out/main.js"))
                                      :autorefresh-resource-without-leading-slash? (boolean (io/resource "public/js/compiled/out/main.js"))
                                      :css-and-media "real repository build outputs and sources"
                                      :javascript "three disposable fixtures, two already minified"}
                         :pipeline (dissoc pipeline :files)} reports)]
      (io/make-parents "target/middleware-audit.json")
      (spit "target/middleware-audit.json" (json/write-str report :indent true))
      (println (json/write-str report :indent true))))
  (shutdown-agents))

(apply -main *command-line-args*)
