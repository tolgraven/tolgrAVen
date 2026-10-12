(ns tolgraven.modules.search.api
  (:require [tolgraven.schema.http :as schemas]
            [tolgraven.supabase.integrations :as integrations]))

(defn routes []
  (vec (remove nil?
   [["/integrations/search" {:parameters {:query schemas/search-query}
                            :get (fn [request]
                                   (let [query (get-in request [:parameters :query])]
                                     (integrations/response! #(integrations/search! (:collection query)
                                                               (into {} (map (fn [[k v]] [(name k) v])) query)))))}]])))
