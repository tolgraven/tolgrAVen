(ns tolgraven.middleware
  (:require
    [tolgraven.env :refer [defaults]]
    [tolgraven.build.site :refer [when-enabled]]
    [cognitect.transit :as transit]
    [clojure.tools.logging :as log]
    [tolgraven.layout :as layout :refer [error-page]]
    [tolgraven.streaming :as streaming]
    [tolgraven.middleware.formats :as formats]
    [tolgraven.middleware.assets :as delivery]
    [tolgraven.middleware.multipart :as multipart]
    [tolgraven.middleware.ranges :as ranges]
    [tolgraven.config :refer [env]]
    [muuntaja.middleware :refer [wrap-format wrap-params]]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [ring.middleware.flash :refer [wrap-flash]]
    [ring.middleware.file :refer [wrap-file]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.middleware.gzip :as gzip :refer [wrap-gzip]]
    [ring.middleware.partial-content :refer [wrap-partial-content]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.default-charset :refer [wrap-default-charset]]
    [ring.middleware.content-length :refer [wrap-content-length]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    ; Optional :experiments profile:
    ; [ring.middleware.ratelimit :refer [wrap-ratelimit]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    ; [ring.middleware.cors :refer [wrap-cors]]
    [ring.adapter.undertow.middleware.session :refer [wrap-session]]
    [ring.middleware.defaults :refer [site-defaults wrap-defaults secure-site-defaults]]
    ; [ring-middleware-csp.core :refer [wrap-csp]]
    ; [ring.middleware.logger :refer [wrap-with-logger]]
    [optimus.prime :as optimus]
    [tolgraven.loader.style-catalog :as styles]
    [optimus.assets :as assets]
    [optimus.optimizations :as optimizations]
    [optimus.strategies :as strategies]
    [optimus-img-transform.core :refer [transform-images]]
    [taoensso.timbre :as timbre :refer [debug log error]]
    ))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (error t (.getMessage t))
        ;; The inner session/CSRF binding has already unwound. Error rendering
        ;; must not depend on it or expose exception messages in public HTML.
        (binding [ring.middleware.anti-forgery/*anti-forgery-token* nil]
          (let [status (:status (ex-data t))]
            (error-page {:request req
                         :status (if (and (integer? status) (<= 400 status 599)) status 500)
                         :title "Request could not be completed"
                         :message "Please try again."})))))))

(defn wrap-csrf [handler]
  (wrap-anti-forgery
    handler
    {:error-response
     (error-page
       {:status 403
        :title "Invalid anti-forgery token"})}))


(defn wrap-formats [handler]
  (let [wrapped (-> handler wrap-params (wrap-format formats/instance))]
    (fn [request]
      ((if (:websocket? request) ;; disable wrap-formats for websockets
         handler                 ;; since they're not compatible with this middleware
         wrapped)
       request))))

(defn get-assets []
  (concat
   (assets/load-bundle "public"
                       "styles.css"
                       ["/css/tolgraven/main.min.css"])
   (assets/load-bundles "public"
                        styles/stylesheet-bundles)
   (assets/load-bundle "public"
                       "icons.css"
                       ["/css/tolgraven/icons.min.css"])
   (assets/load-bundles "public"
                        {"main.js" [(str "/js/compiled/out/"
                                         (or (get-in (layout/browser-modules) [:main :output-name]) "main.js"))]})
   (assets/load-bundles "public"
                        {"document-head.js" ["/js/document/head.js"]
                         "document-entrance.js" ["/js/document/entrance.js"]})
   (assets/load-assets "public"
                       [#"/img/.+\.(png?|svg?|gif?|jpg?|jpeg?|webp|avif)$"
                        #"/media/.*\.(jpg?|jpeg?|webp|avif|mp4|webm)$"])
   (assets/load-assets "public"
                       ["/sitemap.xml" "/robots.txt"])))


(defn module-source-maps
  "Keep Shadow maps reachable when Optimus relocates a module into /bundles/."
  [loaded-assets]
  (map (fn [{:keys [path contents] :as asset}]
         (if (and (string? contents) (string/starts-with? (or path "") "/js/compiled/out/"))
           (update asset :contents string/replace
                   #"(?m)(//# sourceMappingURL=)([^/\s]+\.js\.map)$"
                   "$1/js/compiled/out/$2")
           asset)) loaded-assets))

(defn optimize-all [assets options]
  (-> assets
      module-source-maps
      (optimizations/minify-js-assets options)
      ; (optimizations/minify-css-assets options) ;my css breaks it ofc ; not anymore apparently ; well it's already minified by autoprefixer or? could anyways
      (optimizations/inline-css-imports)
      (optimizations/concatenate-bundles options)
      (transform-images {:regexp #"(/media/.*\.jpg)|(/img/.*\.(jpg|png))" ; Only transform originals, not webp/avif
                         :quality 0.80
                         :progressive true}) ; webp/avif already optimized at creation time
      (optimizations/add-cache-busted-expires-headers)
      (optimizations/add-last-modified-headers)))

(defn serve-live-assets-maybe-autorefresh
  "Refresh a realized development inventory with a bounded TTL, without a watcher."
  [app get-assets optimize options]
  (delivery/serve-assets app get-assets optimize (assoc options :cache-live-assets 2000)))

(defn wrap-optimus
  [app]
  (-> app
      (optimus/wrap
       get-assets
       (if (:dev env)
         optimizations/none
         optimize-all)
       (if (:dev env)
         serve-live-assets-maybe-autorefresh ; like serve-live-assets but with watcher recompiling instead of timeout
         delivery/serve-assets)
       {;:cache-live-assets 60000
        :uglify-js {:mangle-names false}})))

(defn wrap-log "Log req then pass on unchanged"
  [handler id]
  (fn [req]
    (timbre/debug (str "Wrap-log " id ", handler:") handler " request: " req)
    (handler req)))

(defn wrap-gzip-content-aware
  "Compress useful response representations, independent of optional Fetch Metadata."
  [handler]
  (fn [request]
    (let [response (handler request)
          headers (:headers response)
          content-type (or (get headers "Content-Type") (get headers "content-type") "")
          vary (or (get headers "Vary") (get headers "vary"))
          varied? (some #{"*" "accept-encoding"}
                        (map string/trim (string/split (string/lower-case (or vary "")) #",")))]
      (if (and (#{200 206 304} (:status response))
               (re-find #"(?i)^(?:text/|application/(?:javascript|json|transit\+json|xml)|image/svg\+xml)" content-type)
               (not (or (get headers "Content-Encoding") (get headers "content-encoding"))))
        (streaming/gzip-response
          request
          (update response :headers #(-> %
                                         (dissoc "vary")
                                         (assoc "Vary" (if varied? vary
                                                          (str (when (seq vary) (str vary ", "))
                                                               "Accept-Encoding"))))))
        response))))

(defn wrap-module-cache [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (not (:dev env)) (= 200 (:status response))
               (re-find #"(?i)^(?:application|text)/javascript(?:;|$)"
                        (get-in response [:headers "Content-Type"] ""))
               (re-matches #"/js/compiled/out/[a-zA-Z0-9_-]+\.[a-fA-F0-9]{32}\.js" (:uri request "")))
        (assoc-in response [:headers "Cache-Control"] "public, max-age=31536000, immutable")
        response))))

(defn wrap-media-cache
  "Image/video filenames are immutable: publish changed content under a new name.
   Preserve existing Optimus headers; plain successful media gets the same policy
   for whole bodies and range responses. Errors and other resource types are excluded."
  [handler]
  (fn [request]
    (let [response (handler request)
          headers (:headers response)
          content-type (or (get headers "Content-Type") (get headers "content-type") "")]
      (if (and (not (:dev env))
               (#{200 206} (:status response))
               (re-matches #"/(?:img|media)/.+" (:uri request ""))
               (re-find #"(?i)^(?:image|video)/" content-type)
               (not (or (get headers "Cache-Control") (get headers "cache-control"))))
        (assoc-in response [:headers "Cache-Control"] "public, max-age=31536000, immutable")
        response))))

(defn wrap-base [handler]
  (let [options (if (or (env :dev) (env :test) (env :stage))
                  site-defaults
                  (assoc secure-site-defaults :proxy true))
        browser-options (-> options
                            (dissoc :proxy :static)
                            (assoc :security (select-keys (:security options) [:anti-forgery]))
                            (assoc :responses {})
                            (assoc-in [:params :multipart] false)
                            #_(assoc-in [:security :anti-forgery] true)) ; preserved CSRF experiment
        authorize (or (when-enabled :main :media
                        (requiring-resolve 'tolgraven.media.cms/wrap-webhook-authorization))
                      identity)]
    (-> ((:middleware defaults) handler)
        ; wrap-flash
        ;; Keep global browser CSRF, cookies, sessions and form parameters intact.
        ;; Public GET/HEAD assets do not create personalized session cookies.
        (wrap-defaults browser-options)
        delivery/wrap-public-resources
        ; (wrap-file "resources/public" {:prefer-handler? true}) ; preserved file-serving experiment
        wrap-optimus
        ;; One response policy covers plain resources, Optimus and routed bodies.
        (wrap-content-type {:mime-types {"avif" "image/avif"}})
        (wrap-default-charset "utf-8")
        wrap-content-length
        wrap-module-cache
        ; (wrap-log "Wrapped gzip")
        wrap-not-modified
        ; (wrap-csp {:policy csp})
        ranges/wrap-ranges
        wrap-media-cache
        wrap-gzip-content-aware
        multipart/wrap-multipart
        ;; Only the exact Strapi POST can supply Ring's existing safe header,
        ;; after the same constant-time bearer validation used by the handler.
        authorize
        ;; API routes own Muuntaja; HTML and return-template JSON are already encoded.
        ; wrap-with-logger
        wrap-internal-error
        ;; Proxy/HTTPS and security response headers also cover early failures.
        (wrap-defaults {:proxy (:proxy options)
                        :security (dissoc (:security options) :anti-forgery)}))))

; wrap-cors maybe good for api side once/if we get a real api heh. would need configure to only run on prod tho...
; wrap-ratelimit sounds good to have for potential larger sites with actual exposure.
; wrap-csp still need to investigate...
