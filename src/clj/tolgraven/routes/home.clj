(ns tolgraven.routes.home
  (:require
   [tolgraven.layout :as layout]
   [tolgraven.db.core :as db]
   [tolgraven.middleware :as middleware]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [taoensso.timbre :as timbre]
   [ring.util.response]
   [ring.util.http-response :as response]))

(defn home-page [request]
  (layout/render request "home.html"))

(defn plain-text-header
  [resp]
  (response/header resp "Content-Type" "text/plain; charset=utf-8"))

(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/" {:get home-page}]
   ["/graphiql" {:get (fn [request] (layout/render request "graphiql.html"))}]
   ["/docs" {:get (fn [_]
                    (-> "docs/docs.md" io/resource slurp
                        response/ok
                        plain-text-header))}]
   ["/fart" {:get (fn [_]
                    (-> "butt"
                        response/ok
                        plain-text-header))}]
   ["/blog/:id" {:get (fn [{{:keys [id]} :path-params}]
                    (-> (db/get-blog id)
                        str
                        response/ok
                        plain-text-header))}]
   ; ["/blog-new" {:post (fn [{{:keys [id]} :path-params}]
   ;                       (-> (db/add-blog id)
   ;                           str
   ;                           response/ok
   ;                           plain-text-header))}]
   ["/user/:id" {:get (fn [{{:keys [id]} :path-params}]
                        (let [user (db/get-user id)]
                          (timbre/debug user)
                          (-> (or user {})
                              str
                              response/ok
                              plain-text-header)))}]])

