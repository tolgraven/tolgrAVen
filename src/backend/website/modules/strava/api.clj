(ns website.modules.strava.api
  (:require [website.schema.http :as schemas]
            [website.supabase.integrations :as integrations]))

(defn routes []
  (vec (remove nil?
   [["/integrations/settings" {:get (fn [_] (integrations/response! integrations/settings!))}]
   ["/integrations/strava" {:parameters {:query [:map [:path schemas/strava-path]]}
                                :get (fn [request] (integrations/response! #(integrations/strava! (get-in request [:parameters :query :path]))))}]
   ["/integrations/intervals" {:parameters {:query [:map [:path schemas/intervals-path]]}
                                :get (fn [request] (integrations/response! #(integrations/intervals! (get-in request [:parameters :query :path]))))}]])))
