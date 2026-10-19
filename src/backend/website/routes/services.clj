(ns website.routes.services
  (:require
    [website.build.site :refer [when-enabled]]
    [reitit.swagger :as swagger]
    [reitit.openapi :as openapi]
    [reitit.swagger-ui :as swagger-ui]
    [reitit.ring.coercion :as coercion]
    [website.schema.http :as schemas]
    [website.browser-resources.schema :as resource-schema]
    [website.supabase.schema :as store-schema]
    [website.config :as config]
    [website.deployment :as deployment]
    [website.deployment.schema :as deployment-schema]
    [website.site-catalog :as catalog]
    [website.site :as site]
    [website.env :as environment]
    [website.oembed :as oembed]
    [website.components.oembed.contract :as oembed-contract]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [website.middleware.multipart :as multipart]
    [reitit.ring.middleware.parameters :as parameters]
    [clj-http.client :as http]
    [ring.util.http-response :as response]
    [taoensso.timbre :as timbre]
    [clojure.data.json :as json]
    [website.middleware.formats :as formats]
    [website.middleware.exception :as exception]
    [website.content.service :as content]
    [website.supabase.api :as supabase-api]
    [website.supabase.integrations :as integrations]
    [clojure.java.io :as io]
    [clojure.string :as string]))

(defn plain-text-header [resp]
  (response/header resp "Content-Type" "text/plain; charset=utf-8"))

(defn- ordinary-service-routes []
  (vec (remove nil? ["/api"
   {:coercion schemas/coercion
    :muuntaja formats/instance
    :swagger {:id ::api}
    :openapi {:id ::api}
    :middleware (cond-> [parameters/parameters-middleware       ;; query-params & form-params
                 muuntaja/format-negotiate-middleware   ;; content-negotiation
                 muuntaja/format-response-middleware    ;; encoding response body
                 exception/exception-middleware         ;; exception handling
                 muuntaja/format-request-middleware     ;; decoding request body
                 coercion/coerce-request-middleware     ;; coercing request parameters
                 multipart/coercion-middleware]
                  (config/validation-enabled?) (conj coercion/coerce-response-middleware))}       ;; multipart

   ;; OpenAPI is the UI default; the original Swagger document remains compatible.
   ["" {:no-doc true
        :swagger {:info {:title "my-api"
                         :description "https://cljdoc.org/d/metosin/reitit"}}}

    ["/openapi.json"
     {:get {:handler (openapi/create-openapi-handler)
            :openapi {:openapi "3.1.0"
                      :info {:title "tolgrAVen API" :version "1.0.0"}}}}]

    ["/swagger.json"
     {:get (swagger/create-swagger-handler)}]

    ["/api-docs/*"
     {:get (swagger-ui/create-swagger-ui-handler
             {:url "/api/openapi.json"
              :config {:validator-url nil}})}]]



   ["/oembed"
    {:get {:summary "Get oembed data for a URL"
           :parameters {:query schemas/url-query}
           :responses {200 {:body oembed-contract/result}}
           :handler (fn [{{{:keys [url]} :query} :parameters}]
                      (oembed/response! url))}}]



   ["/content/bootstrap" {:get (fn [_] (content/response nil))}]
   ["/deployment" {:get {:responses {200 {:body deployment-schema/metadata}}
                         :handler deployment/response}}]
   ["/browser-resources"
    {:get {:summary "Public optional-script settings acquired after page readiness"
           :responses {200 {:body resource-schema/settings}}
           :handler (fn [_]
                      (response/ok
                        {:analytics (if (false? (get-in config/env [:analytics :enabled]))
                                      {}
                                      (select-keys (:analytics config/env) [:google-id]))}))}}]
   ["/content" {:get (fn [request]
                       (content/response (some-> (get-in request [:query-params "keys"])
                                                  (string/split #","))))}]




   (when-enabled :main :services ["/integrations/strapi" {:parameters {:query [:map [:path schemas/strapi-path]]}
                                :get (fn [request] (integrations/response! #(integrations/strapi! (get-in request [:parameters :query :path]))))}])
   (when-enabled :main :services ["/integrations/image" {:parameters {:query schemas/image-query}
                           :get (fn [request]
                                (try (integrations/image-response! (get-in request [:parameters :query :url])
                                                                   (get-in request [:parameters :query :transforms]))
                                     (catch Exception _ {:status 400 :body {:error "Invalid image request"}})))}])

   (when site/uses-supabase? ["/supabase/settings"
    {:get {:summary "Public browser settings for direct Supabase reads"
           :handler (fn [_]
                      (supabase-api/settings-response))}}])






   (when site/uses-supabase? ["/supabase/store/query"
    {:post {:summary "Query Supabase-backed store data using document and collection paths"
            :parameters {:body store-schema/query}
            :handler (fn [{{query-map :body} :parameters}]
                       (supabase-api/query-response query-map))}}])



   ["/math"
    {:tags ["math"]}

    ["/plus"
     {:get {:summary "plus with spec query parameters"
            :parameters {:query schemas/operands}
            :responses {200 {:body schemas/positive-total}}
            :handler (fn [{{{:keys [x y]} :query} :parameters}]
                       {:status 200
                        :body {:total (+ x y)}})}
      :post {:summary "plus with spec body parameters"
             :parameters {:body schemas/operands}
             :responses {200 {:body schemas/positive-total}}
             :handler (fn [{{{:keys [x y]} :body} :parameters}]
                        {:status 200
                         :body {:total (+ x y)}})}}]]

   ["/files"
    {:tags ["files"]}

    ["/download"
     {:get {:summary "downloads a file"
            :swagger {:produces ["image/png"]}
            :openapi {:responses {"200" {:description "PNG image"
                                         :content {"image/png" {:schema {:type "string"
                                                                         :format "binary"}}}}}}
            :handler (fn [_]
                       {:status 200
                        :headers {"Content-Type" "image/png"}
                        :body (-> "public/img/warning_clojure.png"
                                  (io/resource)
                                  (io/input-stream))})}}]]])))

(defn service-routes []
  (cond-> (into (ordinary-service-routes)
                (mapcat (fn [[_ {:keys [routes]}]]
                          (when routes ((requiring-resolve routes))))
                        (sort-by key catalog/modules)))
    (:development? environment/defaults)
    (into ((requiring-resolve 'website.dev.source/routes)))))
