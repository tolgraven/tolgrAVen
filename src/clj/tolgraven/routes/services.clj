(ns tolgraven.routes.services
  (:require
    [reitit.swagger :as swagger]
    [reitit.swagger-ui :as swagger-ui]
    [reitit.ring.coercion :as coercion]
    [reitit.coercion.spec :as spec-coercion]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.multipart :as multipart]
    [reitit.ring.middleware.parameters :as parameters]
    [ring.middleware.basic-authentication :refer [wrap-basic-authentication]]
    [muuntaja.core :as m]
    [ring.util.http-response :as response]
    [taoensso.timbre :as timbre]
    [tolgraven.middleware.formats :as formats]
    [tolgraven.middleware.exception :as exception]
    [clojure.java.io :as io]
    [clojure.edn :as edn])
  (:import [java.io File FileInputStream FileOutputStream]))

(defn plain-text-header [resp]
  (response/header resp "Content-Type" "text/plain; charset=utf-8"))

(defn authenticated? [username password]
  (= [username password]
     [(System/getenv "AUTH_USER") (System/getenv "AUTH_PASS")]))

; file upload: 
(def resource-path "resources/public/img/uploads/")

(defn file-path [path & [filename]]
  (java.net.URLDecoder/decode
    (str path File/separator filename)
    "utf-8"))

(defn upload-file "uploads a file to the target folder"
  [path {:keys [tempfile size filename]}]
  (with-open [in (io/input-stream tempfile)
              out (io/output-stream (file-path path filename))]
    (io/copy in out)))


(defn service-routes []
  ["/api"
   {:coercion spec-coercion/coercion
    :muuntaja formats/instance
    :swagger {:id ::api}
    :middleware [parameters/parameters-middleware       ;; query-params & form-params
                 muuntaja/format-negotiate-middleware   ;; content-negotiation
                 muuntaja/format-response-middleware    ;; encoding response body
                 exception/exception-middleware         ;; exception handling
                 muuntaja/format-request-middleware     ;; decoding request body
                 coercion/coerce-response-middleware    ;; coercing response bodys
                 coercion/coerce-request-middleware     ;; coercing request parameters
                 multipart/multipart-middleware]}       ;; multipart

   ;; swagger documentation
   ["" {:no-doc true
        :swagger {:info {:title "my-api"
                         :description "https://cljdoc.org/d/metosin/reitit"}}}

    ["/swagger.json"
     {:get (swagger/create-swagger-handler)}]

    ["/api-docs/*"
     {:get (swagger-ui/create-swagger-ui-handler
             {:url "/api/swagger.json"
              :config {:validator-url nil}})}]]

   ["/blog" {:summary "Get specific blog-post"
             :parameters {:query {:id int?}}
             :get (fn [{{{:keys [id]} :query} :parameters}]
                    (-> "not"
                        response/ok
                        plain-text-header))}]
   ["/blog/:id" {:get (fn [{{:keys [id]} :path-params}]
                          (timbre/debug "Blog: " id (string? id))
                    (-> "nor"
                        response/ok
                        #_plain-text-header))}]
   ["/user/:id" {:get (fn [{{:keys [id]} :path-params}]
                        (let [user "never" #_(db/get-user id)]
                          (timbre/debug user)
                          (-> (or user {})
                              ; pr-str
                              response/ok
                              #_plain-text-header)))}]

   ["/firebase-settings" ;XXX obviously needs to be behind basic auth. well no proper auth because otherwise same issue of giving client info. whole lot better than having in code tho...
    {;:middleware [#(wrap-basic-authentication % authenticated?)] ; not enough need to hook it up
     :get (fn [_]
            (-> "firebase/firebase-project.edn"
                io/resource
                slurp
                edn/read-string
                (merge {:apiKey (System/getenv "FIREBASE_API_KEY")})
                (#(m/encode formats/instance "application/transit+json" %))
                response/ok
                (response/content-type "application/transit+json")))}]
   
   ["/math"
    {:swagger {:tags ["math"]}}

    ["/plus"
     {:get {:summary "plus with spec query parameters"
            :parameters {:query {:x int?, :y int?}}
            :responses {200 {:body {:total pos-int?}}}
            :handler (fn [{{{:keys [x y]} :query} :parameters}]
                       {:status 200
                        :body {:total (+ x y)}})}
      :post {:summary "plus with spec body parameters"
             :parameters {:body {:x int?, :y int?}}
             :responses {200 {:body {:total pos-int?}}}
             :handler (fn [{{{:keys [x y]} :body} :parameters}]
                        {:status 200
                         :body {:total (+ x y)}})}}]]

   ["/files"
    {:swagger {:tags ["files"]}}

    ["/upload"
     {:post {:summary "upload a file"
             :parameters {:multipart {:file multipart/temp-file-part}}
             :responses {200 {:body {:name string?, :size int?}}}
             :handler (fn [{{{:keys [file]} :multipart} :parameters}]
                        (upload-file resource-path file)
                        {:status 200
                         :body {:name (:filename file)
                                :size (:size file)}})}}]

    ["/download"
     {:get {:summary "downloads a file"
            :swagger {:produces ["image/png"]}
            :handler (fn [_]
                       {:status 200
                        :headers {"Content-Type" "image/png"}
                        :body (-> "public/img/warning_clojure.png"
                                  (io/resource)
                                  (io/input-stream))})}}]]])
