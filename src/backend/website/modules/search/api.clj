(ns website.modules.search.api
  (:require [website.schema.http :as schemas]
            [website.supabase.integrations :as integrations]))

(defn routes []
  (vec (remove nil?
   [["/integrations/search" {:parameters {:query schemas/search-query}
                            :get (fn [request]
                                   (let [query (get-in request [:parameters :query])]
                                     (integrations/response! #(integrations/search! (:collection query)
                                                               (into {} (map (fn [[k v]] [(name k) v])) query)))))}]])))
