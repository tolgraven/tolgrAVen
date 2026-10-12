(ns tolgraven.modules.docs.api
  (:require [tolgraven.schema.http :as schemas]
            [ring.util.http-response :as response]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn plain-text-header [resp]
  (response/header resp "Content-Type" "text/plain; charset=utf-8"))

(defn routes []
  (vec (remove nil?
   [["/doc" {:summary "Get doc stuff, with extra html stripped out"
            :parameters {:query schemas/docs-query}
            :get (fn [{{{:keys [path]} :query} :parameters}]
                  (when-not (string/blank? path)
                    (-> (str "docs/codox/" path ".html")
                        io/resource
                        slurp
                        (string/replace #"^[\s\S]*<body[^\>]*>([\s\S]*)<\/body>[\s\S]*$" "$1") ; strip html and head body tags
                        response/ok
                        plain-text-header)))}]])))
