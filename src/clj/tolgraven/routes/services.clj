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
    [clj-http.client :as http]
    [ring.util.http-response :as response]
    [taoensso.timbre :as timbre]
    [clojure.data.json :as json]
    [tolgraven.middleware.formats :as formats]
    [tolgraven.middleware.exception :as exception]
    [tolgraven.services.gpt :as gpt]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.edn :as edn])
  (:import [java.io File FileInputStream FileOutputStream]))

(defn plain-text-header [resp]
  (response/header resp "Content-Type" "text/plain; charset=utf-8"))

(defn authenticated? [username password]
  (= [username password]
     [(System/getenv "AUTH_USER") (System/getenv "AUTH_PASS")]))

; file upload: 
(def upload-path "resources/public/img/uploads/")

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
   
   ["/doc" {:summary "Get doc stuff, with extra html stripped out"
            :parameters {:query {:path string?}}
            :get (fn [{{{:keys [path]} :query} :parameters}]
                  (when-not (string/blank? path)
                    (-> (str "docs/codox/" path ".html")
                        io/resource
                        slurp
                        (string/replace #"^[\s\S]*<body[^\>]*>([\s\S]*)<\/body>[\s\S]*$" "$1") ; strip html and head body tags
                        response/ok
                        plain-text-header)))}]

   ["/gpt"
    {:post {:summary "Poll OpenAI API"
            :parameters {:body {:messages coll?}}
            ; :responses {200 {:body {:reply string?}}}
            :handler (fn [{{{:keys [messages]} :body} :parameters :as params}]
                       (let [reply (gpt/chat messages)]
                         {:status 200
                          :body reply}))}}]

   ["/send-contact-email"
    {:post {:summary "Send email to self and contact"
            :parameters {:body {:name string? :email string? :title string? :message string?}}
            ; :responses {200 {:body {:reply string?}}}
            :handler
            (fn [{{{:keys [name email title message]} :body} :parameters :as params}]
              (let [uri (str "https://script.google.com/macros/s/"
                             (System/getenv "GOOGLE_SENDMAIL_SCRIPT")
                             "/exec")
                    reply (http/post uri
                                     {:body (json/write-str {:name name
                                                             :email email
                                                             :title title
                                                             :message message})
                                      :headers {"Content-Type" "text/plain;charset=utf-8"}
                                      :redirect "follow"})]
                (timbre/debug "her emailz... " email)
                (timbre/debug "reply is " reply)
                {:status 200
                 :body reply}))}}]

   ["/firebase-settings" ;XXX obviously needs to be behind basic auth. well no proper auth because otherwise same issue of giving client info. whole lot better than having in code tho...
    ; OBVIOUSLY NOT IN THIS INSTANCE ALSO ALL KEYS GOING TO CLIENT WILL ALWAYS BE THEIRS.
    ; this is why stuff is scoped :P
    ; anyways leave this in the concept of sending env stuff, but especially modded edn stuff, to client surely useful later.
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
                        (upload-file upload-path file)
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
