(ns tolgraven.middleware
  (:require
    [tolgraven.env :refer [defaults]]
    [cognitect.transit :as transit]
    [clojure.tools.logging :as log]
    [tolgraven.layout :refer [error-page]]
    [tolgraven.middleware.formats :as formats]
    [tolgraven.config :refer [env]]
    [muuntaja.middleware :refer [wrap-format wrap-params]]
    [clojure.java.io :as io]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [ring.middleware.flash :refer [wrap-flash]]
    [ring.middleware.file :refer [wrap-file]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.middleware.gzip :as gzip :refer [wrap-gzip]]
    [ring.middleware.partial-content :refer [wrap-partial-content]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.ratelimit :refer [wrap-ratelimit]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    ; [ring.middleware.cors :refer [wrap-cors]]
    [ring.adapter.undertow.middleware.session :refer [wrap-session]]
    [ring.middleware.defaults :refer [site-defaults wrap-defaults secure-site-defaults]]
    ; [ring-middleware-csp.core :refer [wrap-csp]]
    [ring.middleware.logger :refer [wrap-with-logger]]
    [optimus.prime :as optimus]
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
        (error-page {:request req
                     :status 500
                     :title "Internal error"
                     :message (.getMessage t)})))))

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
                       ["/css/tolgraven/main.min.css"]) ;should have a list for these, edn ting...
   (assets/load-bundles "public"
                        {"main.js" ["/js/compiled/out/main.js"]})
   (assets/load-assets "public"
                       [#"/img/.+\.(png?|svg?|gif?|jpg?|jpeg?|webp|avif)$"
                        #"/media/.*\.(jpg?|jpeg?|webp|avif|mp4|webm)$"])
   (assets/load-assets "public"
                       ["/sitemap.xml" "/robots.txt"])))


(defn optimize-all [assets options]
  (-> assets
      (optimizations/minify-js-assets options)
      ; (optimizations/minify-css-assets options) ;my css breaks it ofc ; not anymore apparently ; well it's already minified by autoprefixer or? could anyways
      (optimizations/inline-css-imports)
      (optimizations/concatenate-bundles options)
      (transform-images {:regexp #"(/media/.*\.jpg)|(/img/.*\.(jpg|png))" ; Only transform originals, not webp/avif
                         :quality 0.80
                         :progressive true}) ; webp/avif already optimized at creation time
      ; (optimizations/add-cache-busted-expires-headers) ; pisses off lighthouse. not sure why would want media to instantly expire anyways so
      (optimizations/add-last-modified-headers)))

(defonce serve-live-assets-maybe-autorefresh
  (if (nil? (io/resource "/js/compiled/out/main.js"))
     strategies/serve-live-assets
     strategies/serve-live-assets-autorefresh))

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
         ; strategies/serve-live-assets ; -autorefresh can't deal with cleans, errors on startup if no app.js. But I guess not too often have to do that, or well sometimes all the time but then not for weeks... check file 
         strategies/serve-frozen-assets)
       {;:cache-live-assets 60000
        :uglify-js {:mangle-names false}})))

(defn wrap-log "Log req then pass on unchanged"
  [handler id]
  (fn [req]
    (timbre/debug (str "Wrap-log " id ", handler:") handler " request: " req)
    (handler req)))

(defn wrap-gzip-content-aware
  "Needed presumablY because optimus confuses the gzip middleware due to not raw files or whatever? At least it tries to gzip inappropriate stuff..."
  [handler]
  (fn [{:keys [headers] :as req}]
    (if (some->> (get headers "sec-fetch-dest") ; could also look at content-tupe?
                 (re-find #"image|video")
                 some?)
      (handler req)
      ((gzip/wrap-gzip handler) req))))

(defn wrap-base [handler]
  (-> ((:middleware defaults) handler)
      ; wrap-flash
      (wrap-defaults
        (-> (if (or (env :dev) (env :test) (env :stage))
              site-defaults
              (-> secure-site-defaults ;use ssl and setup for rev proxy
                  (assoc-in [:proxy] true)))
            #_(assoc-in [:security :anti-forgery] true) ; what's with this? from before we injected csrf or?
            )) ; why was there a dissoc :session? cause it's about what middleware we request to wrap us with. cause gotta choose either above or through defaults... get duplicate session warnings now that uncommented hmm.
      (wrap-resource "public" {:prefer-handler? true}) ; hopefully fixes gzipping of images and shit causing 50% ballooning of sizes :O
      ; (wrap-file "resources/public" {:prefer-handler? true}) ; hopefully fixes gzipping of images and shit causing 50% ballooning of sizes :O
      wrap-optimus
      wrap-content-type ; must go after wrap-resource. checks file ext and adds correct content type
      ; gzip/wrap-gzip
      wrap-gzip-content-aware
      ; (wrap-log "Wrapped gzip")
      wrap-not-modified ; guess this doesnt work cause optimus gens new files tho..
      ; (wrap-csp {:policy csp})
      wrap-partial-content
      wrap-multipart-params
      wrap-formats
      ; wrap-with-logger
      wrap-internal-error))

; wrap-cors maybe good for api side once/if we get a real api heh. would need configure to only run on prod tho...
; wrap-ratelimit sounds good to have for potential larger sites with actual exposure.
; wrap-csp still need to investigate...
