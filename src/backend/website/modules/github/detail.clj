(ns website.modules.github.detail
  "Server-owned public commit details through the existing bounded provider."
  (:require [clojure.string :as string]
            [website.cache.inflight :as inflight]
            [website.modules.github.provider :as provider]
            [website.modules.github.settings :as settings]
            [website.modules.github.detail-schema :as schema]
            [website.validation :as validation])
  (:import [java.util.concurrent Semaphore]
           [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

(defonce ^:private *cache (atom {}))
(defonce ^:private slots (Semaphore. 4))

(defn public-detail [user repo value]
  (when-not (and (vector? (:files value)) (vector? (:parents value)))
    (throw (ex-info "Commit file or parent metadata is unavailable" {})))
  (let [commit (:commit value)
        commit-url (fn [sha] (str "https://github.com/" user "/" repo "/commit/" sha))]
    (validation/check! "Public GitHub commit detail" schema/result
      {:sha (:sha value)
       :html_url (commit-url (:sha value))
       :author (merge {:login nil, :avatar_url nil} (select-keys (:author value) [:login :avatar_url]))
       :commit {:message (:message commit)
                :author (merge {:name nil} (select-keys (:author commit) [:name :date]))
                :committer (merge {:name nil} (select-keys (:committer commit) [:name :date]))}
       :parents (mapv (fn [{:keys [sha]}] {:sha sha, :html_url (commit-url sha)}) (:parents value))
       :stats (select-keys (:stats value) [:additions :deletions :total])
       :files (mapv #(select-keys % [:filename :status :additions :deletions :changes :patch
                                    :previous_filename :raw_url :blob_url :contents_url]) (:files value))})))

(defn- public! [path]
  ;; A configured token may read private repositories. Never publish those
  ;; details, including immutable responses fetched while the repo was public.
  (when-not (false? (:private (provider/request! :get path {})))
    (throw (ex-info "Repository is not public" {:upstream-status 404}))))

(defn- unavailable [error]
  (let [{:keys [upstream-status retry-at]} (ex-data error)]
    {:status (if (= 404 upstream-status) 404 503)
     :headers (cond-> {"Cache-Control" "no-store"}
                retry-at (assoc "Retry-After" (str (min 86400 (max 1 (- retry-at (quot (System/currentTimeMillis) 1000)))))))
     :body {:error (if (= 404 upstream-status)
                     "This public commit is unavailable"
                     "Commit details are temporarily unavailable")}}))

(defn- encode-path [path]
  (string/join "/" (map #(string/replace (URLEncoder/encode % StandardCharsets/UTF_8) "+" "%20")
                         (string/split path #"/"))))

(defn- fetch! [user repo sha page kind path]
  (let [repository-path (provider/repository-path user repo)]
    (case kind
      :commit
      (let [value (provider/request! :get (str repository-path "/commits/" sha)
                    (if page {:query-params {:page page, :per_page 100}} {}))
            result (public-detail user repo value)]
        (when-not (string/starts-with? (string/lower-case (:sha result)) sha)
          (throw (ex-info "Commit identity did not match" {})))
        result)
      :patch (provider/request-patch! (str repository-path "/commits/" sha))
      :source (validation/check! "Public GitHub source file" schema/source
                (select-keys (provider/request! :get (str repository-path "/contents/" (encode-path path))
                               {:query-params {:ref sha}})
                             [:type :encoding :content :size])))))

(defn response! [{:keys [user repo sha page kind path] :or {kind :commit}}]
  (if-not (and (provider/user? user) (provider/repo? repo)
               (string? sha) (re-matches #"[a-fA-F0-9]{7,64}" sha)
               (#{:commit :patch :source} kind)
               (or (nil? page) (and (integer? page) (<= 1 page 30)))
               (or (not= :source kind)
                   (and (re-matches #"[a-fA-F0-9]{40,64}" sha) (schema/safe-path? path))))
    {:status 400, :headers {"Cache-Control" "no-store"}, :body {:error "Invalid commit reference"}}
    (if-not (.tryAcquire slots)
      (unavailable nil)
      (try
        (let [selected (settings/selection!)
              target [(string/lower-case user) (string/lower-case repo)]
              configured (when selected (mapv string/lower-case [(:user selected) (:repo selected)]))]
          (if-not (= target configured)
            {:status 404, :headers {"Cache-Control" "no-store"}, :body {:error "Repository is not configured"}}
            (let [[user' repo'] target
                  sha' (string/lower-case sha)
                  repository-path (provider/repository-path user' repo')
                  _ (public! repository-path)
                  now (System/currentTimeMillis)
                  cache-key [user' repo' sha' kind page path]
                  [owner? entry] (inflight/acquire! *cache cache-key now 900000 16)]
              (when owner?
                (let [response (try
                                 (let [result (fetch! user' repo' sha' page kind path)]
                                   (public! repository-path)
                                   {:status 200
                                    ;; Visibility is checked on every delivery; a shared HTTP
                                    ;; cache must not retain public details after privatization.
                                    :headers (cond-> {"Cache-Control" "no-store"}
                                               (= :patch kind) (assoc "Content-Type" "text/plain; charset=utf-8"))
                                    :body result})
                                 (catch Exception error (unavailable error)))]
                  (when-not (= 200 (:status response))
                    (inflight/shorten! *cache cache-key entry (+ now 60000)))
                  (deliver (:value entry) response)))
              (if entry (deref (:value entry) 45000 (unavailable nil)) (unavailable nil)))))
        (catch Exception error (unavailable error))
        (finally (.release slots))))))
