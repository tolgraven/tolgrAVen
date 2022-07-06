(ns tolgraven.routes.home
  (:require
   [tolgraven.layout :as layout]
   ; [tolgraven.db.sql :as sql]
   [tolgraven.middleware :as middleware]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [taoensso.timbre :as timbre]
   [ring.util.response]
   [ring.util.http-response :as response]))

(defn home-page [request]
  (layout/render-home request))

(defn plain-text-header
  [resp]
  (response/content-type resp "text/plain; charset=utf-8"))

(defn home-routes []
  [""
   {:middleware [#_middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/" {:get home-page}]
   ["/blog*" {:get home-page}]
   ["/log*" {:get home-page}]
   ["/test*" {:get home-page}]
   ["/docs*" {:get home-page}]
   ["/about*" {:get home-page}]
   ["/services*" {:get home-page}]
   ["/cv*" {:get home-page}]
   ["/hire*" {:get home-page}]
   ["/site/*" {:get home-page}]
   ["/api/docs" {:get (fn [_]
                    (-> "docs/docs.md" io/resource slurp
                        response/ok
                        plain-text-header))}] ; isnt this the exact equivalent of serving asset directly?
   ["/user/:id" {:get (fn [{{:keys [id]} :path-params}]
                        (let [user "none"]
                          (timbre/debug user)
                          (-> (or user {})
                              str
                              response/ok
                              plain-text-header)))}]])

