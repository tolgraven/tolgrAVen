(ns website.middleware
  (:require
    [website.env :refer [defaults]]
    [website.build.site :refer [when-enabled]]
    [cognitect.transit :as transit]
    [clojure.tools.logging :as log]
    [website.layout :as layout :refer [error-page]]
    [website.streaming :as streaming]
    [website.middleware.formats :as formats]
    [website.middleware.assets :as delivery]
    [website.middleware.asset-manifest :as manifest]
    [website.site :as site]
    [website.middleware.multipart :as multipart]
    [website.middleware.ranges :as ranges]
    [website.config :refer [env]]
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
    [website.loader.style-catalog :as styles]
    [optimus.assets :as assets]
    [website.fonts.icons :as icons]
    [taoensso.telemere.timbre :as timbre :refer [debug log error]]
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

(def document-bundles
  {"document-head.js" ["/js/document/head.js"]
   "document-entrance.js" ["/js/document/entrance.js"]})

(defn get-bundled-assets
  "Load the declared CSS/JS owners and their references for the live dev build."
  []
  (concat
    (assets/load-bundle "public" "styles.css" ["/css/website/main.min.css"])
    (assets/load-bundles "public" styles/stylesheet-bundles)
    (assets/load-bundle "public" "icons.css" ["/css/website/icons.min.css"])
    ;; Unrelated asset requests may realize the dev inventory before release.
    ;; Never retain bytes from an old unoptimized document build in that cache.
    (when (if-let [ready? (:document-ready? defaults)] (ready?) true)
      (assets/load-bundles "public" document-bundles))))

(defn get-assets
  "Complete build-time inventory; runtime production imports its prepared result."
  []
  (concat
    (get-bundled-assets)
    (assets/load-assets "public"
      [#"/img/.+\.(png?|svg?|gif?|jpg?|jpeg?|webp|avif)$"
       #"/media/.*\.(jpg?|jpeg?|webp|avif|mp4|webm)$"])
    (assets/load-assets "public" ["/sitemap.xml" "/robots.txt"])))

(defn asset-build-settings []
  {:browser-modules (layout/browser-modules)
   :site site/config
   :styles styles/stylesheet-bundles})


(defn optimize-all
  "Compatibility entry point for build/audit callers; never invoked at startup."
  [assets options]
  ((requiring-resolve 'website.build.assets/optimize) assets options))

(defn serve-live-assets-maybe-autorefresh
  "Refresh a realized development inventory with a bounded TTL, without a watcher."
  [app get-assets optimize options]
  (delivery/serve-assets app get-assets optimize (assoc options :cache-live-assets 2000)))
  ; :cache-live-assets 60000 ; preserved longer-cache experiment

(defn ensure-production-assets! []
  (when-not (or (:dev env) (:test env) (:stage env))
    (let [main (:main (layout/browser-modules))]
      (when (or (not (re-matches #"main\.[a-fA-F0-9]{32}\.js" (or (:output-name main) "")))
                (some #{'devtools.preload 'shadow.cljs.devtools.client.browser}
                      (:entries main)))
        (throw (ex-info "Production browser assets are missing or use a development build. Run bb prod:build before starting local prod."
                        {:output-name (:output-name main)})))
      (let [missing (vec (for [path (mapcat val document-bundles)
                              :when (nil? (io/resource (str "public" path)))]
                          path))]
        (when (seq missing)
          (throw (ex-info "Production document bootstrap is missing. Run bb prod:build before starting local prod."
                          {:build :document
                           :missing-assets missing}))))
      (when-not (io/resource manifest/resource-path)
        (throw (ex-info "Production prepared assets are missing. Run bb prod:build before starting local prod."
                        {:build :assets
                         :missing-assets [manifest/resource-path]}))))))

(defn wrap-optimus [app]
  (ensure-production-assets!)
  (if (:dev env)
    (serve-live-assets-maybe-autorefresh app get-bundled-assets (fn [files _] files) {})
    (let [inventory (manifest/read-prepared! (asset-build-settings))]
      (delivery/serve-assets app (constantly inventory) (fn [files _] files) {}))))

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
    (let [response (handler request)
          javascript? (re-find #"(?i)^(?:application|text)/javascript(?:;|$)"
                              (get-in response [:headers "Content-Type"] ""))
          uri (:uri request "")]
      (cond
        ;; Development URLs keep their names across Shadow restarts, but their
        ;; bytes carry the current worker's connection identity.
        (and (:dev env) javascript?
             (re-matches #"/js/(?:compiled/out|document|return)/.+" uri))
        (assoc-in response [:headers "Cache-Control"] "no-store")

        (and (not (:dev env)) (= 200 (:status response)) javascript?
             (re-matches #"/js/compiled/out/[a-zA-Z0-9_-]+\.[a-fA-F0-9]{32}\.js" uri))
        (assoc-in response [:headers "Cache-Control"] "public, max-age=31536000, immutable")

        :else response))))

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

(defn wrap-document-readiness
  "Development documents and their bootstrap wait for the isolated release.
   Static images and unrelated API calls remain available during app compilation."
  [handler]
  (if-let [ready! (:document-ready! defaults)]
    (fn [request]
      (let [uri (:uri request "")
            static? (some #(string/starts-with? uri %)
                          ["/css/" "/js/" "/img/" "/media/" "/fonts/" "/bundles/" "/assets/"])
            api? (or (= uri "/api") (string/starts-with? uri "/api/"))
            html? (re-find #"(?i)(?:^|,)\s*text/html(?:\s*[;,]|$)"
                           (get-in request [:headers "accept"] ""))]
        (when (and (#{:get :head} (:request-method request))
                   (or (= uri "/api/page-return-template")
                       (string/starts-with? uri "/js/document/")
                       (re-find #"^/bundles/(?:[^/]+/)*document-(?:head|entrance)\.js$" uri)
                       (and (not api?) (not static?)
                            (or html? (not (re-find #"\.[^/]+$" uri))))))
          (ready!))
        (handler request)))
    handler))

(defn wrap-style-readiness [handler]
  (if-let [ready! (:styles-ready! defaults)]
    (fn [request]
      (let [uri (:uri request "")
            asset? (or (string/ends-with? uri ".css")
                       (re-matches #"/js/compiled/out/[^/]+\.js" uri))
            document? (and (not (string/starts-with? uri "/api/"))
                           (string/includes? (get-in request [:headers "accept"] "") "text/html"))]
        (handler (if (and (#{:get :head} (:request-method request)) (or asset? document?))
                   (assoc request :styles/revision (ready!))
                   request))))
    handler))

(defn wrap-base [handler]
  (let [options (if (or (env :dev) (env :test) (env :stage)
                        (false? (env :https-required)))
                  site-defaults
                  (assoc secure-site-defaults :proxy true))
        browser-options (-> options
                            (dissoc :proxy :static)
                            (assoc :security (select-keys (:security options) [:anti-forgery]))
                            (assoc :responses {})
                            (assoc-in [:params :multipart] false)
                            #_(assoc-in [:security :anti-forgery] true)) ; preserved CSRF experiment
        authorize (or (when-enabled :main :media
                        (requiring-resolve 'website.media.cms/wrap-webhook-authorization))
                      identity)]
    (-> ((:middleware defaults) handler)
        ; wrap-flash
        ;; Keep global browser CSRF, cookies, sessions and form parameters intact.
        ;; Public GET/HEAD assets do not create personalized session cookies.
        (wrap-defaults browser-options)
        delivery/wrap-public-resources
        ; (wrap-file "resources/public" {:prefer-handler? true}) ; preserved file-serving experiment
        wrap-optimus
        icons/wrap-assets
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
        wrap-document-readiness
        wrap-style-readiness
        wrap-internal-error
        ;; Proxy/HTTPS and security response headers also cover early failures.
        ((or (when-enabled :neovim nil
               (requiring-resolve 'website.modules.neovim.http/wrap-isolation)) identity))
        (wrap-defaults {:proxy (:proxy options)
                        :security (dissoc (:security options) :anti-forgery)}))))

; wrap-cors maybe good for api side once/if we get a real api heh. would need configure to only run on prod tho...
; wrap-ratelimit sounds good to have for potential larger sites with actual exposure.
; wrap-csp still need to investigate...
