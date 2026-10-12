(ns build.resolve-site
  "Resolve sanitized capabilities from the existing CMS origin only."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [tolgraven.build.site :as site]))

(def snapshot "resources/site.overrides.edn")
(def max-bytes 262144)

(defn endpoint [origin]
  (let [url (java.net.URI. origin)
        local? (contains? #{"localhost" "127.0.0.1" "[::1]"} (.getHost url))]
    (when-not (and (.getHost url) (nil? (.getUserInfo url))
                   (nil? (.getQuery url)) (nil? (.getFragment url))
                   (contains? #{"" "/"} (.getPath url))
                   (or (= "https" (.getScheme url))
                       (and local? (= "http" (.getScheme url)))))
      (throw (ex-info "STRAPI_URL must be an HTTPS origin; local tests may use HTTP" {})))
    (str (string/replace origin #"/+$" "") "/api/site-config")))

(defn resolve-config! [origin token]
  (if (string/blank? origin)
    {:modules {}}
    (let [url (endpoint origin)]
      (when (string/blank? token)
        (throw (ex-info "Configured Strapi resolution requires STRAPI_READ_TOKEN" {})))
      (let [response (try (http/get url {:headers {"Authorization" (str "Bearer " token)
                                             "Accept" "application/json"}
                                   :client (http/client {:follow-redirects :never :connect-timeout 5000})
                                   :throw false
                                   :timeout 15000})
                       (catch Exception _ (throw (ex-info "Strapi site config request could not complete" {}))))
            status (:status response)]
        (cond
          (= status 404) {:modules {}}
          (not= status 200) (throw (ex-info "Strapi site config request failed" {:status status}))
          :else
          (let [body (:body response)]
            (when (> (count (.getBytes body "UTF-8")) max-bytes)
              (throw (ex-info "Strapi site config exceeds the size limit" {})))
            (let [{:keys [version config]} (try (json/parse-string body true)
                                            (catch Exception _
                                              (throw (ex-info "Strapi site config is not valid JSON" {}))))]
              (when-not (= version 1) (throw (ex-info "Unsupported site config version" {})))
              (site/validate! (merge {:modules {}} config)))))))))

(defn -main [& _]
  (let [config (resolve-config! (System/getenv "STRAPI_URL") (System/getenv "STRAPI_READ_TOKEN"))
        temporary (str snapshot ".tmp")]
    (spit temporary (pr-str config))
    (java.nio.file.Files/move (.toPath (io/file temporary)) (.toPath (io/file snapshot))
                             (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    (println "Resolved site capability overrides; credentials are never stored in the snapshot.")))
