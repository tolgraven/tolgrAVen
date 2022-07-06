(ns tolgraven.middleware
  (:require
    [tolgraven.env :refer [defaults]]
    [cognitect.transit :as transit]
    [clojure.tools.logging :as log]
    [tolgraven.layout :refer [error-page]]
    [tolgraven.middleware.formats :as formats]
    [tolgraven.config :refer [env]]
    [muuntaja.middleware :refer [wrap-format wrap-params]]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [ring.middleware.flash :refer [wrap-flash]]
    [ring.middleware.file :refer [wrap-file]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.middleware.gzip :as gzip]
    [ring.middleware.partial-content :refer [wrap-partial-content]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.adapter.undertow.middleware.session :refer [wrap-session]]
    [ring.middleware.defaults :refer [site-defaults wrap-defaults secure-site-defaults]]
    ; [ring-middleware-csp.core :refer [wrap-csp]]
    [optimus.prime :as optimus]
    [optimus.assets :as assets]
    [optimus.optimizations :as optimizations]
    [optimus.strategies :as strategies]
    [optimus.optimizations.add-cache-busted-expires-headers]
    [optimus.optimizations.concatenate-bundles]
    [optimus.optimizations.minify]
    [optimus.optimizations.add-last-modified-headers]
    [optimus.optimizations.inline-css-imports]
    [optimus-img-transform.core :refer [transform-images]]
    [potemkin :refer [import-vars]]))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (log/error t (.getMessage t))
        (error-page {:status 500
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
                       ["/css/tolgraven/main.min.css" ;should have a list for these, edn ting...
                        ; "/css/fontawesome.css"
                        ; "/css/brands.min.css"
                        ; "/css/solid.css"
                        ])
   (assets/load-bundles "public"
                        {"app.js" ["/js/compiled/app.js"] })
   (assets/load-assets "public"
                       [#"/img/.+\.png$"
                        #"/img/.+\.svg$"
                        #"/img/.+\.gif$"
                        #"/img/.+\.jpeg$"
                        #"/img/.+\.jpg$"
                        #"/media/.*\.jpg"])))

(import-vars [optimus.optimizations.minify
              minify-js-assets
              #_minify-css-assets] ;my css breaks it ofc
             [optimus.optimizations.concatenate-bundles
              concatenate-bundles]
             [optimus.optimizations.add-cache-busted-expires-headers
              add-cache-busted-expires-headers]
             [optimus.optimizations.add-last-modified-headers
              add-last-modified-headers]
             [optimus.optimizations.inline-css-imports
              inline-css-imports])

(defn optimize-all [assets options]
  (-> assets
      (minify-js-assets options)
      ; (minify-css-assets options) ;my css breaks it ofc ; not anymore apparently ; well it's already minified by autoprefixer or? could anyways
      (inline-css-imports)
      (concatenate-bundles options)
      (transform-images {:regexp #"(/media/.*\.jpg)|(/img/.*\.(jpg|png))" ; in-place which would be baddd on dev but only runs on prod so
                         :quality 0.75
                         :progressive true})
      ; (add-cache-busted-expires-headers)
      (add-last-modified-headers)))

(defn wrap-optimus
  [app]
  (-> app
      (optimus/wrap
       get-assets
       (if (:dev env)
         optimizations/none
         optimize-all)
       (if (:dev env)
         strategies/serve-live-assets
         strategies/serve-frozen-assets))))


(defn wrap-base [handler]
  (-> ((:middleware defaults) handler)
      ; wrap-flash
      ; (wrap-session {:cookie-attrs {:http-only true}})
      (wrap-defaults
        (-> (if (or (env :dev) (env :test))
              site-defaults
              (-> secure-site-defaults ;use ssl and setup for rev proxy
                  (assoc-in [:proxy] false)))
            (assoc-in [:security :anti-forgery] true) ; what's with this? from before we injected csrf or?
            )) ; why was there a dissoc :session? cause it's about what middleware we request to wrap us with. cause gotta choose either above or through defaults... get duplicate session warnings now that uncommented hmm.
      wrap-optimus
      (wrap-resource "public") ; hopefully fixes gzipping of images and shit causing 50% ballooning of sizes :O
      wrap-content-type ; must go after wrap-resource. checks file ext and adds correct content type
      wrap-not-modified
      ; (wrap-csp {:policy csp})
      gzip/wrap-gzip
      wrap-partial-content
      wrap-multipart-params
      wrap-internal-error))
