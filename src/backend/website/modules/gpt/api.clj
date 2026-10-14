(ns website.modules.gpt.api
  (:require [website.modules.gpt.service :as gpt]
            [website.schema.http :as schemas]
            [website.supabase.schema :as store-schema]
            [website.supabase.auth :as supabase-auth]
            [website.supabase.operations :as supabase-operations]))

(defn routes []
  (vec (remove nil?
   [["/gpt"
    {:post {:summary "Poll OpenAI API"
            :parameters {:body schemas/messages}
            ; :responses {200 {:body {:reply string?}}}
            :handler (fn [{{{:keys [messages]} :body} :parameters :as params}]
                       (supabase-auth/response! params (fn [_] (gpt/chat messages))))}}]
   ["/supabase/documents"
    {:post {:summary "Save a private document owned by the signed-in user"
            :parameters {:body store-schema/document-write}
            :handler (fn [request]
                       (supabase-auth/response! request
                         #(supabase-operations/save-document! % (get-in request [:parameters :body]))))}}]])))
