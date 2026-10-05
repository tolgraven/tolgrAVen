(ns tolgraven.routes.home
  (:require
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [tolgraven.layout :as layout]
   [tolgraven.ssr :as ssr]
   [tolgraven.page-router :as pages]
   [tolgraven.validation :as validation]
   [reitit.coercion :as coercion]
   [ring.util.response]
   [ring.util.http-response :as response]
   [sitemap.core :as sitemap]))

(defn plain-text-header
  [resp]
  (response/content-type resp "text/plain; charset=utf-8"))

(defn home-page [request]
  (try
    (let [match (pages/request-match (:uri request) (:query-params request))]
      (layout/render-home (assoc request :parameters (:parameters match))))
    (catch clojure.lang.ExceptionInfo error
      (if (= ::coercion/request-coercion (:type (ex-data error)))
        (layout/error-page {:status 400 :title "Invalid page address"
                            :request request :issues (validation/problems (ex-data error))})
        (throw error)))))

(def other-route-names
  ["/log"
   "/test"
   "/docs"
   "/site/"])

(def changing-route-names
  ["/blog"
   "/cv"])

(def main-page-route-names
  ["/about"
   "/services"
   "/hire"])

(def route-names
  (concat main-page-route-names
          changing-route-names
          other-route-names))

#_(defn home-routes []
  (concat [""
           {:middleware [;middleware/wrap-csrf ; csrf not really needed since no http auth and whatnot
                         #_middleware/wrap-formats]}
           ["/" {:get home-page}]
           ["/api/docs" {:get (fn [_]
                                (-> "docs/docs.md" io/resource slurp
                                    response/ok
                                    plain-text-header))}]]
          (mapv (fn [route]
                  [(str route "*") {:get home-page}])
                route-names)))
(defn home-routes []
  [""
   {:middleware [;middleware/wrap-csrf ; csrf not really needed since no http auth and whatnot
                 #_middleware/wrap-formats]}
   ["/" {:get home-page}]
   ["/return-worker.js"
    {:get (fn [_]
            (if-let [resource (io/resource "public/js/return/return-worker.js")]
              {:status 200 :headers {"Content-Type" "text/javascript; charset=utf-8"
                                    "Service-Worker-Allowed" "/" "Cache-Control" "no-cache"}
               ;; Changes even when the worker source itself did not change:
               ;; browser worker updates then retire previous build caches.
               :body (str "self.TOLGRAVEN_RETURN_BUILD="
                          (json/write-str (str (ssr/renderer-build))) ";\n" (slurp resource))}
              (response/not-found)))}]
   ["/api/page-return-template"
    {:get (fn [request]
            {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
             :body (json/write-str (layout/return-template request))})}]
   ["/blog*" {:get home-page}]
   ["/not-found" {:get home-page}]
   ["/client-oauth*" {:get home-page}]
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
                          (-> (or user {})                                                               
                              str                                                                        
                              response/ok                                                                
                              plain-text-header)))}]])                                                   

(defn gen-sitemap!
  "Generates a sitemap for the home page and its routes."
  []
  (let [base-url "https://tolgraven.se"
        filename "resources/public/sitemap.xml"
        main-page-entries (mapv (fn [route]
                                 {:loc (str base-url route)
                                  :lastmod "2025-01-01"
                                  :changefreq "monthly"
                                  :priority 1.0})
                               (conj main-page-route-names "/"))
        changing-route-names (mapv (fn [route]
                                    {:loc (str base-url route)
                                     :lastmod "2025-06-01"
                                     :changefreq "weekly"
                                     :priority 0.9})
                                  changing-route-names)
        other-route-names (mapv (fn [route]
                                 {:loc (str base-url route)
                                  :lastmod "2025-01-01"
                                  :changefreq "yearly"
                                  :priority 0.3})
                               other-route-names)
        file (io/file filename)]
    (spit file "" :append false)
    (->>
     (sitemap/generate-sitemap (concat main-page-entries
                                       changing-route-names
                                       other-route-names))
     (sitemap/save-sitemap file)
     #_sitemap/validate-sitemap
     #_count)))
