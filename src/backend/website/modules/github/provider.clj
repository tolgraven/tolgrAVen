(ns website.modules.github.provider
  "Bounded GitHub JSON transport. Credentials and provider errors stay server-side."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [website.config :as config])
  (:import [java.io InputStream]
           [java.nio.charset StandardCharsets]))

(defn token [] (or (get-in config/env [:github :token]) (:github-token config/env)))
(defn user? [value] (boolean (and (string? value) (re-matches #"[A-Za-z0-9][A-Za-z0-9-]{0,38}" value))))
(defn repo? [value] (boolean (and (string? value) (not (#{"." ".."} value))
                                            (re-matches #"[A-Za-z0-9_.-]{1,100}" value))))
(defn repository-path [user repo]
  (when-not (and (user? user) (repo? repo)) (throw (ex-info "Invalid repository" {:status 400})))
  (str "/repos/" user "/" repo))

(defn- request-body! [method path options body-limit accept]
  (when-not (re-matches #"/repos/[A-Za-z0-9-]+/[A-Za-z0-9_.-]+(?:/branches|/tags|/commits(?:/[a-fA-F0-9]{7,64})?|/compare/[a-fA-F0-9]{40,64}\.\.\.[a-fA-F0-9]{40,64}|/contents/(?:[A-Za-z0-9_.~/-]|%[0-9A-Fa-f]{2})+)?" path)
    (throw (ex-info "Invalid GitHub API path" {:status 400})))
  (let [response (http/request
                   (merge options
                          {:method method
                           :url (str "https://api.github.com" path)
                           :headers (cond-> {"Accept" accept
                                             "X-GitHub-Api-Version" "2022-11-28"
                                             "User-Agent" "website-github-archive"}
                                      (seq (token)) (assoc "Authorization" (str "Bearer " (token))))
                           :as :stream
                           :follow-redirects false
                           :throw-exceptions false
                           :conn-timeout 3000
                           :socket-timeout 10000}))
        body (with-open [stream ^InputStream (:body response)]
               (let [bytes (.readNBytes stream (inc body-limit))]
                 (when (> (alength bytes) body-limit)
                   (throw (ex-info "GitHub response exceeds limit" {:status 503, :reason :body-limit})))
                 (String. bytes StandardCharsets/UTF_8)))
        status (:status response)]
    (when-not (= 200 status)
      (let [reset-at (try (Long/parseLong (get-in response [:headers "x-ratelimit-reset"] "0"))
                          (catch Exception _ 0))]
        (throw (ex-info "GitHub request unavailable"
                       {:status 503
                        :upstream-status status
                        :retry-at (max (+ 60 (quot (System/currentTimeMillis) 1000)) reset-at)}))))
    body))

(defn request! [method path options]
  (let [value (json/read-str (request-body! method path options 4194304 "application/vnd.github+json") :key-fn keyword)]
    (when (seq (:errors value)) (throw (ex-info "GitHub query unavailable" {:status 503})))
    value))

(defn request-patch! [path]
  (request-body! :get path {} 1048576 "application/vnd.github.patch"))
