(ns tolgraven.supabase.integrations
  (:require [clj-http.client :as http]
            [tolgraven.validation :as validation]
            [tolgraven.schema.http :as schema]
            [clojure.string :as string]
            [tolgraven.platform.supabase :as platform])
  (:import [java.net URI URLEncoder]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn config! [service]
  (or (:config (first (:body (platform/request! :get "service_configs"
                       {:query-params {"service" (str "eq." service) "select" "config" "limit" 1}})))) {}))

(defn- upstream! [method url opts]
  (let [response (http/request (merge {:method method :url url :as :json :coerce :always
                                       :conn-timeout 3000 :socket-timeout 15000
                                       :throw-exceptions false} opts))]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info "Integration request failed" {:status 503})))
    (:body response)))

(defn response! [f]
  (try {:status 200 :body (f)}
       ;; Never include upstream exceptions: they can contain Authorization headers.
       (catch Exception e {:status (or (:status (ex-data e)) 503)
                           :body {:error "Integration unavailable or request invalid"}})))

(defn- path! [path schema]
  (when-let [issues (validation/explain schema path)]
    (throw (ex-info (validation/message issues) {:status 400})))
  path)

(defn settings! []
  (let [strava (config! "strava/auth")]
    {:strava (select-keys strava [:athlete_id :intervals_athlete_id])}))

(defonce *strava-refresh-lock (Object.))
(defn- strava-token! []
  (locking *strava-refresh-lock
    (let [config (config! "strava/auth")]
      (if (< (+ (quot (System/currentTimeMillis) 1000) 60) (or (:expires_at config) 0))
        (:access_token config)
        (let [tokens (upstream! :post "https://www.strava.com/api/v3/oauth/token"
                       {:form-params (assoc (select-keys config [:client_id :client_secret :refresh_token])
                                           :grant_type "refresh_token")})
              updated (merge config (select-keys tokens [:access_token :refresh_token :expires_at]))]
          (platform/request! :patch "service_configs"
            {:query-params {"service" "eq.strava/auth"} :content-type :json
             :form-params {:config updated}})
          (:access_token updated))))))

(defn strava! [path]
  (path! path schema/strava-path)
  (upstream! :get (str "https://www.strava.com/api/v3/" path)
             {:headers {"Authorization" (str "Bearer " (strava-token!))}}))

(defn intervals! [path]
  (path! path schema/intervals-path)
  (let [{:keys [intervals_athlete_id intervals_api_key]} (config! "strava/auth")]
    (upstream! :get (str "https://intervals.icu/api/v1/athlete/" intervals_athlete_id "/"
                        (string/replace path "{ext}" ""))
      {:headers {"Authorization" (str "Basic " (.encodeToString (Base64/getEncoder)
                                              (.getBytes (str "API_KEY:" intervals_api_key) "UTF-8")))}})))

(defn instagram! []
  (let [auth (config! "instagram/auth")
        cached (delay {:posts (config! "instagram/posts")})]
    (if (seq (:access_token auth))
      (try
        (let [posts (:data (upstream! :get "https://graph.instagram.com/me/media"
                            {:query-params {:access_token (:access_token auth)
                                            :fields "caption,id,media_type,media_url,username,timestamp"}}))]
          {:posts (into {} (map (juxt :id identity)) posts)})
        ;; Imported media remains available when an old token expires or the
        ;; upstream endpoint is unavailable. No credential is returned publicly.
        (catch Exception _ @cached))
      @cached)))

(defn- positive-number! [value default maximum]
  (let [value (if (nil? value) default
                 (try (Long/parseLong (str value)) (catch Exception _ 0)))]
    (when-not (<= 1 value maximum)
      (throw (ex-info "Invalid search paging" {:status 400})))
    value))

(defn search! [collection parameters]
  (when-not (and (#{"blog-posts" "blog-comments"} collection)
                 (string? (get parameters "q"))
                 (<= 1 (count (get parameters "q")) 200))
    (throw (ex-info "Invalid search query" {:status 400})))
  (:body (platform/request! :post "rpc/tolgraven_search"
           {:content-type :json
            :form-params {:p_collection collection :p_query (get parameters "q")
                          :p_page (positive-number! (get parameters "page") 1 10000)
                          :p_per_page (positive-number! (get parameters "per_page") 15 100)}})))

(defn strapi! [path]
  (path! path schema/strapi-path)
  (let [{:keys [url read-api-key]} (config! "strapi/auth")]
    (when-not (seq url) (throw (ex-info "CMS not configured" {:status 503})))
    (upstream! :get (str (string/replace url #"/+$" "") path)
               {:headers {"Authorization" (str "Bearer " read-api-key)}})))

(defn image-response! [url transforms]
  (let [uri (try (when (string? url) (URI. url)) (catch Exception _ nil))
        {:keys [host key allowed-source-hosts loader-network-protected]} (config! "imagor/auth")]
    ;; Signing arbitrary sources grants access to the proxy's network. Require
    ;; an exact host allowlist and a loader configured to block private addresses
    ;; at connection time, including redirect targets and DNS re-resolution.
    (when-not (and uri (= "https" (.getScheme uri)) (.getHost uri)
                   (nil? (.getUserInfo uri)) (nil? (.getFragment uri))
                   (#{-1 443} (.getPort uri))
                   (some #{(string/lower-case (.getHost uri))} allowed-source-hosts))
      (throw (ex-info "Image source is not allowed" {:status 400})))
    ;; Only the transforms currently needed by the UI; no filters accepting
    ;; secondary URLs (watermark, background, etc.) or arbitrary dimensions.
    (when-not (re-matches #"(?:fit-in/)?[1-9][0-9]{0,3}x[1-9][0-9]{0,3}|" (or transforms ""))
      (throw (ex-info "Image transform is not allowed" {:status 400})))
    (when-not (and (true? loader-network-protected) (seq host) (seq key))
      (throw (ex-info "Image proxy not configured safely" {:status 503})))
    (let [path (str (when (seq transforms) (str transforms "/")) url)
          mac (doto (Mac/getInstance "HmacSHA1")
                (.init (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA1")))
          signature (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                      (.doFinal mac (.getBytes path "UTF-8")))]
      {:status 302 :headers {"Location" (str (string/replace host #"/+$" "") "/" signature "/" path)}
       :body ""})))
