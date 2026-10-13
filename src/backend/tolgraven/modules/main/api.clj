(ns tolgraven.modules.main.api
  (:require [tolgraven.build.site :refer [when-enabled]]
            [tolgraven.schema.http :as http-schema]
            [tolgraven.media.schema :as schema]
            [tolgraven.media.pipeline :as pipeline]
            [tolgraven.media.cms :as cms]
            [tolgraven.supabase.auth :as auth]))

(defn- response! [f]
  (try {:status 200 :body (f)}
       (catch Exception error
         {:status (or (:auth/status (ex-data error)) 503)
          :body {:error (if (:auth/status (ex-data error)) (.getMessage error)
                           "Media service is temporarily unavailable")}})))

(defn routes []
  (when-enabled :main :media (pipeline/start!))
  (vec (remove nil?
    [(when-enabled :main :media
       ["/media/resolve"
        {:get {:summary "Resolve a completed public media rendition manifest"
               :parameters {:query schema/resolve-query}
               :responses {200 {:body schema/resolved}}
               :handler (fn [request]
                          (response! #(pipeline/resolve! (get-in request [:parameters :query :src]))))}}])
     (when-enabled :main :media
       (when-enabled :user nil
         ["/supabase/media"
          {:get {:summary "List existing ready media for the authorized image picker"
                 :parameters {:query schema/library-query}
                 :responses {200 {:body schema/library}}
                 :handler (fn [request]
                            (response! #(pipeline/library! (auth/current-user! request)
                                             (get-in request [:parameters :query]))))}
           :post {:summary "Queue a still image as an authorized blogger or administrator"
                  :parameters {:multipart [:map [:file http-schema/upload]]}
                  :responses {200 {:body schema/upload-response}}
                  :handler (fn [request]
                             (response! #(pipeline/upload! (auth/current-user! request)
                                            (get-in request [:parameters :multipart :file]))))}}]))
     (when-enabled :main :media
       (when-enabled :user nil
         ["/supabase/media/:id"
          {:get {:summary "Read an owned upload's durable processing status"
                 :parameters {:path schema/id-path}
                 :responses {200 {:body schema/upload-response}}
                 :handler (fn [request]
                            (response! #(pipeline/status! (auth/current-user! request)
                                             (get-in request [:parameters :path :id]))))}}]))
     (when-enabled :main :media
       ["/media/strapi/webhook"
        {:post {:summary "Accept an authenticated Strapi media event"
                :handler (fn [request] (response! #(cms/webhook! request)))}}])
     (when-enabled :main :media
       (when-enabled :user nil
         ["/media/strapi/reconcile"
          {:post {:summary "Reconcile one bounded CMS media page as an administrator"
                  :parameters {:query [:map [:?offset [:int {:min 0 :max 100000}]]]}
                  :handler (fn [request]
                             (response! #(cms/reconcile! (auth/current-user! request)
                                             (get-in request [:parameters :query :offset] 0))))}}]))])))
