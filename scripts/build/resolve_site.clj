(ns build.resolve-site
  "Resolve sanitized capabilities from the existing CMS origin only."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [website.build.site :as site]))

(def snapshot "resources/site.overrides.edn")
(def styles-snapshot "resources/site.overrides.css")
(def max-bytes 262144)
(def max-style-bytes 65536)

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

(defn resolve-site! [origin token]
  (if (string/blank? origin)
    {:config {:modules {}}, :styles ""}
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
          (= status 404) {:config {:modules {}}, :styles ""}
          (not= status 200) (throw (ex-info "Strapi site config request failed" {:status status}))
          :else
          (let [body (:body response)]
            (when (> (count (.getBytes body "UTF-8")) max-bytes)
              (throw (ex-info "Strapi site config exceeds the size limit" {})))
            (let [{:keys [version config styles]} (try (json/parse-string body true)
                                            (catch Exception _
                                              (throw (ex-info "Strapi site config is not valid JSON" {}))))]
              (when-not (= version 1) (throw (ex-info "Unsupported site config version" {})))
              (when-not (or (nil? styles)
                            (and (string? styles) (<= (count (.getBytes styles "UTF-8")) max-style-bytes)))
                (throw (ex-info "Site CSS overrides must be text up to 64 KiB" {})))
              {:config (site/validate! (merge {:modules {}} config))
               :styles (or styles "")})))))))

(defn- write-snapshot! [file value]
  (let [temporary (str file ".tmp")]
    (spit temporary value)
    (java.nio.file.Files/move (.toPath (io/file temporary)) (.toPath (io/file file))
                             (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))

(defn -main [& _]
  (let [{:keys [config styles]} (resolve-site! (System/getenv "STRAPI_URL") (System/getenv "STRAPI_READ_TOKEN"))]
    (write-snapshot! snapshot (pr-str config))
    (write-snapshot! styles-snapshot styles)
    (println "Resolved site capability and CSS overrides; credentials are never stored in the snapshots.")))
