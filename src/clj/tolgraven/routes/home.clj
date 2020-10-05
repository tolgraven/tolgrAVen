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

(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/" {:get home-page}]
   ["/graphiql" {:get (fn [request] (layout/render request "graphiql.html"))}]
   ["/docs" {:get (fn [_]
                    (-> (response/ok (-> "docs/docs.md" io/resource slurp))
                        (response/header "Content-Type" "text/plain; charset=utf-8")))}]
   ["/blog" {:get (fn [_]
                    (-> (response/ok (-> "docs/docs.md" io/resource slurp))
                        (response/header "Content-Type" "text/plain; charset=utf-8")))}]
   ["/user/:id" {:get (fn [{:keys [path-params]}]
                        ; (timbre/debug (str "ID: " (pprint id)))
                    ; (-> (response/ok (str (db/get-user (or id "tolgraven"))))
                    (-> (response/ok (db/get-user (:id path-params)))
                        (response/header "Content-Type" "text/plain; charset=utf-8")))}]])

