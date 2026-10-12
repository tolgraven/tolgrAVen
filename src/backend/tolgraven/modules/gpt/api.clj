(ns tolgraven.modules.gpt.api
  (:require [tolgraven.modules.gpt.service :as gpt]
            [tolgraven.schema.http :as schemas]
            [tolgraven.supabase.schema :as store-schema]
            [tolgraven.supabase.auth :as supabase-auth]
            [tolgraven.supabase.operations :as supabase-operations]))

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
