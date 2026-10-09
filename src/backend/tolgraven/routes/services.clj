(ns tolgraven.routes.services
  (:require
    [reitit.swagger :as swagger]
    [reitit.swagger-ui :as swagger-ui]
    [reitit.ring.coercion :as coercion]
    [tolgraven.schema.http :as schemas]
    [tolgraven.supabase.schema :as store-schema]
    [tolgraven.config :as config]
    [tolgraven.oembed :as oembed]
    [tolgraven.components.oembed.contract :as oembed-contract]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.multipart :as multipart]
    [reitit.ring.middleware.parameters :as parameters]
    [clj-http.client :as http]
    [ring.util.http-response :as response]
    [taoensso.timbre :as timbre]
    [clojure.data.json :as json]
    [tolgraven.middleware.formats :as formats]
    [tolgraven.middleware.exception :as exception]
    [tolgraven.modules.gpt.service :as gpt]
    [tolgraven.modules.link-preview.service :as link-preview]
    [tolgraven.modules.link-preview.article :as preview-contract]
    [tolgraven.content.service :as content]
    [tolgraven.supabase.api :as supabase-api]
    [tolgraven.supabase.auth :as supabase-auth]
    [tolgraven.supabase.operations :as supabase-operations]
    [tolgraven.supabase.storage :as storage]
    [tolgraven.supabase.integrations :as integrations]
    [clojure.java.io :as io]
    [clojure.string :as string]))

(defn plain-text-header [resp]
  (response/header resp "Content-Type" "text/plain; charset=utf-8"))

(defn service-routes []
  ["/api"
   {:coercion schemas/coercion
    :muuntaja formats/instance
    :swagger {:id ::api}
    :middleware (cond-> [parameters/parameters-middleware       ;; query-params & form-params
                 muuntaja/format-negotiate-middleware   ;; content-negotiation
                 muuntaja/format-response-middleware    ;; encoding response body
                 exception/exception-middleware         ;; exception handling
                 muuntaja/format-request-middleware     ;; decoding request body
                 coercion/coerce-request-middleware     ;; coercing request parameters
                 multipart/multipart-middleware]
                  (config/validation-enabled?) (conj coercion/coerce-response-middleware))}       ;; multipart

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
            :parameters {:query schemas/docs-query}
            :get (fn [{{{:keys [path]} :query} :parameters}]
                  (when-not (string/blank? path)
                    (-> (str "docs/codox/" path ".html")
                        io/resource
                        slurp
                        (string/replace #"^[\s\S]*<body[^\>]*>([\s\S]*)<\/body>[\s\S]*$" "$1") ; strip html and head body tags
                        response/ok
                        plain-text-header)))}]

   ["/link-preview"
    {:get {:summary "Readable public article preview"
           :parameters {:query preview-contract/query}
           :responses {200 {:body preview-contract/result}}
           :handler (fn [request]
                      (link-preview/response! (get-in request [:parameters :query :url])))}}]

   ["/oembed"
    {:get {:summary "Get oembed data for a URL"
           :parameters {:query schemas/url-query}
           :responses {200 {:body oembed-contract/result}}
           :handler (fn [{{{:keys [url]} :query} :parameters}]
                      (oembed/response! url))}}]

   ["/gpt"
    {:post {:summary "Poll OpenAI API"
            :parameters {:body schemas/messages}
            ; :responses {200 {:body {:reply string?}}}
            :handler (fn [{{{:keys [messages]} :body} :parameters :as params}]
                       (supabase-auth/response! params (fn [_] (gpt/chat messages))))}}]

   ["/send-contact-email"
    {:post {:summary "Send email to self and contact"
            :parameters {:body schemas/contact}
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

   ["/content/bootstrap" {:get (fn [_] (content/response nil))}]
   ["/content" {:get (fn [request]
                       (content/response (some-> (get-in request [:query-params "keys"])
                                                  (string/split #","))))}]

   ["/integrations/settings" {:get (fn [_] (integrations/response! integrations/settings!))}]
   ["/integrations/strava" {:parameters {:query [:map [:path schemas/strava-path]]}
                                :get (fn [request] (integrations/response! #(integrations/strava! (get-in request [:parameters :query :path]))))}]
   ["/integrations/intervals" {:parameters {:query [:map [:path schemas/intervals-path]]}
                                :get (fn [request] (integrations/response! #(integrations/intervals! (get-in request [:parameters :query :path]))))}]
   ["/integrations/instagram" {:get (fn [_] (integrations/response! integrations/instagram!))}]
   ["/integrations/search" {:parameters {:query schemas/search-query}
                            :get (fn [request]
                                   (let [query (get-in request [:parameters :query])]
                                     (integrations/response! #(integrations/search! (:collection query)
                                                               (into {} (map (fn [[k v]] [(name k) v])) query)))))}]
   ["/integrations/strapi" {:parameters {:query [:map [:path schemas/strapi-path]]}
                                :get (fn [request] (integrations/response! #(integrations/strapi! (get-in request [:parameters :query :path]))))}]
   ["/integrations/image" {:parameters {:query schemas/image-query}
                           :get (fn [request]
                                (try (integrations/image-response! (get-in request [:parameters :query :url])
                                                                   (get-in request [:parameters :query :transforms]))
                                     (catch Exception _ {:status 400 :body {:error "Invalid image request"}})))}]

   ["/supabase/settings"
    {:get {:summary "Public browser settings for direct Supabase reads"
           :handler (fn [_]
                      (supabase-api/settings-response))}}]

   ["/supabase/profile"
    {:get {:summary "Read the signed-in user's linked profile"
           :handler (fn [request]
                      (supabase-auth/response! request supabase-auth/profile!))}
     :put {:summary "Update the signed-in user's editable profile fields"
           :parameters {:body store-schema/profile-write}
           :handler (fn [request]
                      (supabase-auth/response!
                       request #(supabase-auth/save-profile! % (get-in request [:parameters :body]))))}}]

   ["/supabase/avatar"
    {:post {:summary "Upload an avatar to Supabase Storage as the signed-in user"
            :parameters {:multipart [:map [:file schemas/upload]]}
            :handler (fn [request]
                       (supabase-auth/response! request
                         #(storage/save-avatar! % (get-in request [:parameters :multipart :file]))))}}]

   ["/supabase/chat"
    {:post {:summary "Post a chat message as the signed-in user"
            :parameters {:body store-schema/chat-write}
            :handler (fn [request]
                       (supabase-auth/response!
                        request #(supabase-operations/post-chat! % (get-in request [:parameters :body]))))}}]

   ["/supabase/comments"
    {:post {:summary "Create a comment or reply as the signed-in user"
            :parameters {:body store-schema/comment-create}
            :handler (fn [request]
                       (supabase-auth/response!
                        request #(supabase-operations/create-comment! % (get-in request [:parameters :body]))))}
     :put {:summary "Edit an owned comment"
           :parameters {:body store-schema/comment-edit}
           :handler (fn [request]
                      (supabase-auth/response!
                       request #(supabase-operations/edit-comment! % (get-in request [:parameters :body]))))}}]

   ["/supabase/votes"
    {:post {:summary "Set or remove the signed-in user's comment vote atomically"
            :parameters {:body store-schema/vote-write}
            :handler (fn [request]
                       (supabase-auth/response!
                        request #(supabase-operations/set-comment-vote! % (get-in request [:parameters :body]))))}}]

   ["/supabase/store/query"
    {:post {:summary "Query Supabase-backed store data using document and collection paths"
            :parameters {:body store-schema/query}
            :handler (fn [{{query-map :body} :parameters}]
                       (supabase-api/query-response query-map))}}]

   ["/supabase/posts"
    {:post {:summary "Publish or edit a post as an authorized author"
            :parameters {:body store-schema/post-write}
            :handler (fn [request]
                       (supabase-auth/response! request
                         #(supabase-operations/save-post! % (get-in request [:parameters :body]))))}}]

   ["/supabase/documents"
    {:post {:summary "Save a private document owned by the signed-in user"
            :parameters {:body store-schema/document-write}
            :handler (fn [request]
                       (supabase-auth/response! request
                         #(supabase-operations/save-document! % (get-in request [:parameters :body]))))}}]

   ["/math"
    {:swagger {:tags ["math"]}}

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
    {:swagger {:tags ["files"]}}

    ["/download"
     {:get {:summary "downloads a file"
            :swagger {:produces ["image/png"]}
            :handler (fn [_]
                       {:status 200
                        :headers {"Content-Type" "image/png"}
                        :body (-> "public/img/warning_clojure.png"
                                  (io/resource)
                                  (io/input-stream))})}}]]])
