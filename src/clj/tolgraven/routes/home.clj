(ns tolgraven.routes.home
  (:require
   [clojure.java.io :as io]
   [tolgraven.layout :as layout]
   [ring.util.response]
   [ring.util.http-response :as response]
   [sitemap.core :as sitemap]))

(defn plain-text-header
  [resp]
  (response/content-type resp "text/plain; charset=utf-8"))

(defn home-page [request]
  (layout/render-home request))

(def other-route-names
  [
   "/log"
   "/test"
   "/docs"])

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

