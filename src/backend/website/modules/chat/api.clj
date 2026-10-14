(ns website.modules.chat.api
  (:require [website.supabase.schema :as store-schema]
            [website.supabase.auth :as supabase-auth]
            [website.supabase.operations :as supabase-operations]))

(defn routes []
  (vec (remove nil?
   [["/supabase/chat"
    {:post {:summary "Post a chat message as the signed-in user"
            :parameters {:body store-schema/chat-write}
            :handler (fn [request]
                       (supabase-auth/response!
                        request #(supabase-operations/post-chat! % (get-in request [:parameters :body]))))}}]])))
