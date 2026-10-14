(ns website.handler
  (:require
    ;; Register the mounted logger before application services and startup logs.
    [website.log]
    [website.middleware :as middleware]
    [website.concurrent :as concurrent]
    [website.validation-server :as validation]
    [website.layout :refer [error-page]]
    [website.routes.home :refer [home-routes]]
    [website.routes.services :refer [service-routes]]
    [reitit.swagger-ui :as swagger-ui]
    [reitit.ring :as ring]
    [website.env :refer [defaults]]
    [mount.core :as mount]))

(mount/defstate init-app
  :start (do
           (middleware/ensure-production-assets!)
           ((or (:init defaults) (fn []))))
  :stop  ((or (:stop defaults) (fn []))))

(mount/defstate app-routes
  :start
  (ring/ring-handler
    (ring/router
      [(home-routes)
       (service-routes)]
      {:validate validation/validate-routes!})
    (ring/routes
      (swagger-ui/create-swagger-ui-handler ; nuke this until/if need?
        {:path   "/swagger-ui"
         :url    "/api/swagger.json"
         :config {:validator-url nil}})
      ;; middleware/assets owns public resources before browser session handling.
      (ring/create-default-handler          ; error/default routes
        (let [get-error (fn [code message]
                          (constantly (error-page {:status code
                                                   :title (str code " - " message)})))]
          {:not-found           (get-error 404 "Page not found")
           :method-not-allowed  (get-error 405 "Not allowed")
           :not-acceptable      (get-error 406 "Not acceptable") })))))

(defn app []
  (concurrent/wrap-async (middleware/wrap-base #'app-routes)))
