(ns tolgraven.media.cms
  "Authenticated Strapi intake and bounded exact-origin media reconciliation."
  (:require [clj-http.client :as http]
            [clj-http.conn-mgr :as connections]
            [clojure.string :as string]
            [tolgraven.config :as config]
            [tolgraven.content.service :as content]
            [tolgraven.media.pipeline :as pipeline]
            [tolgraven.media.schema :as schema]
            [tolgraven.media.storage :as storage]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.validation :as validation])
  (:import [java.net URI InetAddress]
           [java.security MessageDigest]
           [java.io InputStream]
           [org.apache.http.conn DnsResolver]))

(defn- origin [^URI uri]
  [(.getScheme uri) (some-> (.getHost uri) string/lower-case)
   (if (neg? (.getPort uri)) (if (= "https" (.getScheme uri)) 443 80) (.getPort uri))])

(defn source-uri! [url]
  (let [base (:url (content/settings))
        uri (try (.resolve (URI. (str base "/")) (URI. url)) (catch Exception _ nil))
        origins (keep #(try (origin (URI. %)) (catch Exception _ nil))
                      (conj (vec (get-in config/env [:media :strapi-media-origins])) base))]
    (when-not (and uri (#{"http" "https"} (.getScheme uri))
                   (nil? (.getUserInfo uri)) (nil? (.getQuery uri)) (nil? (.getFragment uri))
                   (some #{(origin uri)} origins)
                   (re-matches #"/uploads/[A-Za-z0-9_.-]+" (.getRawPath uri))
                   (not (string/includes? (.getRawPath uri) "..")))
      (storage/fail! 400 "CMS media source is not allowed"))
    uri))

(defn source-bytes! [^URI uri]
  (let [{:keys [url token]} (content/settings)
        same-cms? (= (origin uri) (origin (URI. url)))
        private? (and same-cms? (true? (get-in config/env [:media :allow-private-cms?])))
        resolver (reify DnsResolver
                   (resolve [_ host]
                     (when-not (= (string/lower-case host) (string/lower-case (.getHost uri)))
                       (storage/fail! 400 "Unexpected CMS media host"))
                     (let [addresses (InetAddress/getAllByName host)]
                       ;; Internal CMS networking is an explicit server setting.
                       ;; Check at connection time; redirects are disabled below.
                       (when (and (not private?)
                                  (some #(or (.isAnyLocalAddress ^InetAddress %)
                                             (.isLoopbackAddress ^InetAddress %)
                                             (.isLinkLocalAddress ^InetAddress %)
                                             (.isSiteLocalAddress ^InetAddress %)
                                             (.isMulticastAddress ^InetAddress %)) addresses))
                         (storage/fail! 400 "CMS media address is not allowed"))
                       addresses)))]
    (with-open [manager (connections/make-reusable-conn-manager {:dns-resolver resolver
                                                               :threads 1
                                                               :default-per-route 1})]
      (let [response (try
                       (http/get (str uri) {:connection-manager manager
                                           :headers (if same-cms? {"Authorization" (str "Bearer " token)} {})
                                           :as :stream
                                           :throw-exceptions false
                                           :redirect-strategy :none
                                           :max-redirects 0
                                           :conn-timeout 3000
                                           :socket-timeout 5000})
                       (catch Exception _ (storage/fail! 503 "CMS media download unavailable")))]
        (with-open [input ^InputStream (:body response)]
          (when-not (= 200 (:status response)) (storage/fail! 503 "CMS media download unavailable"))
          (storage/limited-bytes! input storage/max-source-bytes))))))

(defn- metadata [media]
  (cond-> {:external-hash (:hash media)}
    (string? (:alternativeText media)) (assoc :alt (:alternativeText media))))

(defn intake! [media]
  (pipeline/configured!)
  (when (validation/explain schema/strapi-media media) (storage/fail! 400 "Invalid CMS media"))
  (let [key (str "strapi:" (:id media))
        existing (first (:body (platform/request! :get "site_media"
                                {:query-params {"source_key" (str "eq." key)
                                                "active" "eq.true"
                                                "status" "eq.ready"
                                                "order" "updated_at.desc"
                                                "limit" 1}})))
        details (metadata media)]
    (if (and existing (= pipeline/policy-version (:policy_version existing))
             (seq (:hash media))
             (= (:hash media) (get-in existing [:metadata :external-hash]))
             (= (str (source-uri! (:url media))) (:source_url existing)))
      (let [asset (cond-> (dissoc (:asset existing) :alt)
                    (string? (:alt details)) (assoc :alt (:alt details)))]
        (platform/request! :patch "site_media"
          {:query-params {"id" (str "eq." (:id existing))}
           :content-type :json
           :form-params {:metadata details
                         :asset asset
                         :updated_at (str (java.time.Instant/now))}})
        (pipeline/response (assoc existing :asset asset)))
      (let [uri (source-uri! (:url media))]
        (pipeline/queue-bytes! (source-bytes! uri)
          {:source-key key
           :source-type "strapi"
           :source-url (str uri)
           :metadata details})))))

(defn delete! [media]
  (platform/request! :patch "site_media"
    {:query-params {"source_key" (str "eq.strapi:" (:id media))
                    "status" "in.(queued,processing)"}
     :content-type :json
     :form-params {:status "failed" :error "CMS source removed"}})
  (platform/request! :patch "site_media"
    {:query-params {"source_key" (str "eq.strapi:" (:id media))}
     :content-type :json
     :form-params {:active false
                   :source_url nil
                   :updated_at (str (java.time.Instant/now))}})
  ;; Existing post snapshots may still reference these immutable objects. Remove
  ;; the CMS association without deleting pixels from already published content.
  {:accepted true})

(defn webhook! [request]
  (pipeline/configured!)
  (let [secret (or (System/getenv "STRAPI_MEDIA_WEBHOOK_TOKEN")
                   (get-in config/env [:media :strapi-webhook-token]))
        authorization (get-in request [:headers "authorization"])
        payload (:body-params request)]
    (when-not (and (string? secret) (<= 16 (count secret)))
      (storage/fail! 503 "CMS media webhook token is not configured"))
    (when-not (and (seq secret) (string? authorization)
                   (MessageDigest/isEqual (.getBytes (str "Bearer " secret) "UTF-8")
                                          (.getBytes authorization "UTF-8")))
      (storage/fail! 401 "Invalid CMS webhook authorization"))
    (when (validation/explain schema/webhook payload) (storage/fail! 400 "Invalid CMS media event"))
    (cond
      (string/starts-with? (get-in payload [:media :url]) (storage/public-url "media-renditions/"))
      {:ignored true}

      (= "media.delete" (:event payload)) (delete! (:media payload))
      :else (intake! (:media payload)))))

(defn reconcile! [user offset]
  (pipeline/configured!)
  (pipeline/admin! user)
  (let [{:keys [url token]} (content/settings)
        response (http/get (str (string/replace url #"/+$" "") "/api/upload/files")
                   {:headers {"Authorization" (str "Bearer " token)}
                    :query-params {"pagination[start]" offset
                                   "pagination[limit]" 20}
                    :as :json
                    :coerce :always
                    :throw-exceptions false
                    :redirect-strategy :none
                    :conn-timeout 3000
                    :socket-timeout 10000})
        files (:body response)]
    (when-not (and (= 200 (:status response)) (vector? files) (<= (count files) 20))
      (storage/fail! 503 "CMS media inventory unavailable"))
    {:accepted (mapv (fn [media]
                       (if (#{"image/png" "image/jpeg" "image/webp" "image/avif"} (:mime media))
                         (try {:id (:id media) :upload (intake! media)}
                              (catch Exception _ {:id (:id media) :error "Media unavailable or unsupported"}))
                         {:id (:id media) :skipped true})) files)
     :next-offset (when (= 20 (count files)) (+ offset 20))}))
