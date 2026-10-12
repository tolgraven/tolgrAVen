(ns tolgraven.page-router
  "Server router composed from the same local specs as the browser router."
  (:require [reitit.core :as reitit]
            [reitit.coercion :as coercion]
            [tolgraven.schema.http :as schemas]
            [tolgraven.validation-server :as validation]
            [tolgraven.page :as page]
            [tolgraven.content.contract :as content]
            [tolgraven.site-catalog :as catalog]
            [tolgraven.site :as site]))

(def router
  (reitit/router
   (into ["" {:coercion schemas/coercion :parameters {:query schemas/page-query}
             :depends [{:source :strapi :availability :startup :keys content/shell-content}]}]
         (comp (mapcat (fn [[_ {:keys [pages]}]]
                   (when pages (site/routes (var-get (requiring-resolve pages))))))
               (remove nil?))
         (sort-by key catalog/modules))
   {:compile coercion/compile-request-coercers :validate validation/validate-routes!}))

(defn match [path] (reitit/match-by-path router path))

(defn immediate-keys []
  (vec (distinct (concat content/shell-content
                         (mapcat (comp page/immediate-keys second) (reitit/routes router))))))

(defn request-match [path query-params]
  (when-let [match (match path)]
    (let [match (assoc match :query-params query-params)]
      (assoc match :parameters (coercion/coerce! match)))))
