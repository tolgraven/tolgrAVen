(require '[clojure.java.io :as io]
         '[clojure.string :as string]
         '[reitit.ring :as ring]
         '[tolgraven.routes.services :as services]
         '[tolgraven.page-router :as pages]
         '[tolgraven.site :as site]
         '[tolgraven.supabase.query :as query]
         '[tolgraven.provision.supabase.schema :as schema]
         '[tolgraven.platform.supabase :as platform]
         '[tolgraven.config :as config]
         '[tolgraven.schema.http]
         '[tolgraven.supabase.auth]
         '[tolgraven.supabase.storage])
(defn ensure! [condition message] (when-not condition (throw (ex-info message {}))))
(with-redefs [config/env {}]
  (let [router (ring/router [(services/service-routes)])
        handler (ring/ring-handler router (ring/create-default-handler))]
    (doseq [[owner feature paths] [[:blog nil ["/api/supabase/posts"]]
                                 [:blog :comments ["/api/supabase/comments" "/api/supabase/votes"]]
                                 [:chat nil ["/api/supabase/chat"]]
                                 [:gpt nil ["/api/gpt" "/api/supabase/documents"]]
                                 [:user nil ["/api/supabase/profile" "/api/supabase/avatar"]]
                                 [:search nil ["/api/integrations/search"]]
                                 [:strava nil ["/api/integrations/strava" "/api/integrations/intervals"]]
                                 [:instagram nil ["/api/integrations/instagram"]]
                                 [:contact nil ["/api/send-contact-email"]]]
            path paths]
      (when-not (if feature (site/enabled? owner feature) (site/enabled? owner))
        (doseq [method [:get :post :put :delete]]
          (ensure! (= 404 (:status (handler {:request-method method :uri path})))
                   (str "Disabled endpoint reachable: " path)))))
    (doseq [[owner path] [[:home "/"] [:blog "/blog"] [:cv "/cv"] [:docs "/docs"]]]
      (ensure! (= (and (site/enabled? owner) (not= false (get-in site/config [:pages owner])))
                  (some? (pages/match path)))
               (str "Page selection mismatch: " path)))
    (when (= false (get-in site/config [:pages :about]))
      (ensure! (nil? (pages/match "/about")) "Disabled page remains routed"))
    (when-not site/uses-supabase?
      (doseq [path ["/api/supabase/settings" "/api/supabase/store/query"]
              method [:get :post]]
        (ensure! (= 404 (:status (handler {:request-method method :uri path})))
                 (str "No-storage endpoint reachable: " path))))
    (when-not (site/enabled? :blog)
      (ensure! (not (query/public-read-query? {:path-collection [:blog-posts]})) "Disabled blog query allowed")
      (ensure! (empty? (query/seed-load-plan {:path-collection [:blog-posts]})) "Disabled blog SQL planned"))
    (when-not (site/enabled? :blog :comments)
      (ensure! (not (query/public-read-query? {:path-collection [:blog-comments]})) "Disabled comments allowed")
      (ensure! (not-any? #(= "blog_comments" (:table %)) (query/seed-load-plan {:path-collection [:blog-posts]}))
               "Posts still load disabled comments"))
    (doseq [[owner feature namespace symbols]
            [[:user nil 'tolgraven.supabase.auth '[ensure-profile! profile! save-profile!]]
             [:user nil 'tolgraven.supabase.storage '[save-avatar! png-bytes!]]
             [:blog nil 'tolgraven.schema.http '[blog-page blog-post blog-tag]]
             [:docs nil 'tolgraven.schema.http '[docs-page docs-query]]
             [:main :services 'tolgraven.supabase.integrations '[strapi! image-response!]]]]
      (when-not (if feature (site/enabled? owner feature) (site/enabled? owner))
        (doseq [symbol symbols]
          (ensure! (nil? (ns-resolve namespace symbol))
                   (str "Disabled shared implementation remains: " namespace "/" symbol)))))
    (println "PASS: selected pages, endpoint 404s and query allowlists")
    (println "Tables:" (mapv :table (platform/table-definitions)))
    (println "SQL:" (schema/schema-resources))))
