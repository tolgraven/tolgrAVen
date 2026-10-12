(ns tolgraven.modules.blog.api
  (:require [tolgraven.supabase.schema :as store-schema]
            [tolgraven.supabase.auth :as supabase-auth]
            [tolgraven.supabase.operations :as supabase-operations]
            [tolgraven.build.site :refer [when-enabled]]))

(defn routes []
  (vec (remove nil?
   [(when-enabled :blog :comments ["/supabase/comments"
    {:post {:summary "Create a comment or reply as the signed-in user"
            :parameters {:body store-schema/comment-create}
            :handler (fn [request]
                       (supabase-auth/response!
                        request #(supabase-operations/create-comment! % (get-in request [:parameters :body]))))}
     :put {:summary "Edit an owned comment"
           :parameters {:body store-schema/comment-edit}
           :handler (fn [request]
                      (supabase-auth/response!
                       request #(supabase-operations/edit-comment! % (get-in request [:parameters :body]))))}}])
   (when-enabled :blog :comments ["/supabase/votes"
    {:post {:summary "Set or remove the signed-in user's comment vote atomically"
            :parameters {:body store-schema/vote-write}
            :handler (fn [request]
                       (supabase-auth/response!
                        request #(supabase-operations/set-comment-vote! % (get-in request [:parameters :body]))))}}])
   ["/supabase/posts"
    {:post {:summary "Publish or edit a post as an authorized author"
            :parameters {:body store-schema/post-write}
            :handler (fn [request]
                       (supabase-auth/response! request
                         #(supabase-operations/save-post! % (get-in request [:parameters :body]))))}}]])))
