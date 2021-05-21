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
    [ring.middleware.gzip :as gzip]
    [ring.adapter.undertow.middleware.session :refer [wrap-session]]
    [ring.middleware.defaults :refer [site-defaults wrap-defaults secure-site-defaults]]))

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

(defn wrap-base [handler]
  (-> ((:middleware defaults) handler)
      wrap-flash
      (wrap-session {:cookie-attrs {:http-only true}})
      (wrap-defaults
        (-> (if (or (env :dev) (env :test))
              site-defaults
              (-> secure-site-defaults ;use ssl and setup for heroku
                  (assoc-in [:proxy] true)))
            (assoc-in [:security :anti-forgery] false)
            (dissoc :session)))
      gzip/wrap-gzip
      wrap-internal-error))
