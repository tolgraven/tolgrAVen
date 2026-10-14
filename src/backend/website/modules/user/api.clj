(ns website.modules.user.api
  (:require [website.schema.http :as schemas]
            [website.supabase.schema :as store-schema]
            [website.supabase.auth :as supabase-auth]
            [website.supabase.storage :as storage]))

(defn routes []
  (vec (remove nil?
   [["/supabase/profile"
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
                         #(storage/save-avatar! % (get-in request [:parameters :multipart :file]))))}}]])))
