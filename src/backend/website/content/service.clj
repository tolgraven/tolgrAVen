(ns website.content.service
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [website.media.pipeline :as media]
            [website.config :as config]
            [website.page-router :as pages]
            [clojure.tools.logging :as log]
            [mount.core :as mount]
            [website.content.contract :as contract]))

(defonce *cache (atom {}))
(defn settings []
  {:url (or (System/getenv "STRAPI_URL") (:strapi-url config/env))
   :token (or (System/getenv "STRAPI_READ_TOKEN") (:strapi-read-token config/env))})

(defn- image-sources [value]
  (set (filter #(and (string? %)
                    (re-matches #"(?i)(?:https?://|/uploads/|/?img/).+\.(?:png|jpe?g|webp|avif|svg)(?:[?#].*)?" %))
               (tree-seq coll? seq value))))

(defn enrich-media!
  "Acquire ready manifests through the shared public query plan once per content
   snapshot. Media props gain their manifest; string logos keep their public
   contract and use the owning section's sidecar. Missing manifests retain source pixels."
  [sections]
  (let [sources (into {} (map (fn [[key value]] [key (image-sources value)])) sections)
        assets (try (media/assets! (mapcat val sources))
                    (catch Exception _
                      (log/warn "Media manifests unavailable; retaining source images")
                      {}))]
    (into {}
      (for [[key value] sections]
        (let [enriched (walk/postwalk
                         (fn [node]
                           (if-let [asset (and (map? node) (get assets (:src node)))]
                             (merge node (select-keys asset [:src :width :height :renditions :placeholder]) {:asset asset})
                             node)) value)
              owned (select-keys assets (sources key))]
          [key (if (and (map? enriched) (seq owned))
                 (assoc enriched :image-assets owned)
                 enriched)])))))

(defn- load-content! [ks]
  (let [{:keys [url token]} (settings)]
    (if (seq url)
      (let [response (http/get (str (string/replace url #"/+$" "") "/api/site-content")
                              {:headers {"Authorization" (str "Bearer " token)}
                               :query-params {"keys" (string/join "," (map name ks))}
                               :as :json :coerce :always :throw-exceptions false
                               :conn-timeout 3000 :socket-timeout 10000})
            body (:body response)]
        (when-not (and (= 200 (:status response)) (= contract/version (:version body))
                       (map? (:content body)) (every? #(contains? (:content body) %) ks))
          (throw (ex-info "CMS content unavailable" {:status 503})))
        (enrich-media! (select-keys (:content (contract/checked-bundle body ks)) ks)))
      ;; Development/offline seed is server-side only. A configured CMS failure
      ;; never silently replaces the editor's content with the original seed.
      (enrich-media! (select-keys (:content (contract/checked-bundle
                              {:version contract/version
                               :content (select-keys (json/read-str (slurp (io/resource "content-seed.json")) :key-fn keyword) ks)} ks)) ks)))))

(defonce *immediate (atom nil))

(defn refresh-immediate!
  "Publish a complete shell snapshot atomically; a failed refresh keeps the last good value."
  []
  (let [ks (pages/immediate-keys)
        value (contract/normalize-content (load-content! ks))]
    (reset! *immediate {:source (:url (settings)) :content value})
    value))

(defn immediate-content []
  (let [{:keys [source content]} @*immediate]
    (when-not (and content (= source (:url (settings))))
      (throw (ex-info "Page shell has not been initialized" {:status 503})))
    content))

(mount/defstate startup-content
  :start (do
           ;; Startup readiness includes CMS availability. Refresh failures later
           ;; keep serving the last successful public snapshot.
           (refresh-immediate!)
           (let [executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)
                 interval (max 1 (long (get-in config/env [:ssr :shell-refresh-seconds] 120)))]
             (.scheduleWithFixedDelay executor
               ^Runnable (fn [] (try (refresh-immediate!)
                                    (catch Exception _ (log/warn "Page shell refresh failed; retaining loaded content"))))
               interval interval java.util.concurrent.TimeUnit/SECONDS)
             executor))
  :stop (when startup-content (.shutdownNow ^java.util.concurrent.ExecutorService startup-content)))

(defn- read-content! [ks]
  (let [warm (when (= (:source @*immediate) (:url (settings))) (:content @*immediate))
        missing (filterv #(not (contains? warm %)) ks)]
    (merge (select-keys warm ks)
           (when (seq missing) (contract/normalize-content (load-content! missing))))))

(defn- acquire-sections! [source ks now]
  ;; Only cache/promise ownership is serialized. CMS and media I/O must never
  ;; hold this lock: disjoint sections belong to independent module consumers.
  (locking *cache
    (reduce
      (fn [requests k]
        (let [entry (get-in @*cache [source k])]
          (if (<= (- now (get entry :at 0)) 30000)
            (assoc-in requests [:content k] (:value entry))
            (if-let [pending (:pending entry)]
              (assoc-in requests [:waiting k] pending)
              (let [pending' (promise)]
                (swap! *cache assoc-in [source k :pending] pending')
                (-> requests
                    (assoc-in [:owned k] pending')
                    (assoc-in [:waiting k] pending')))))))
      {:content {}, :owned {}, :waiting {}} ks)))

(defn- load-sections! [source owned ks now]
  (when (seq owned)
    (try
      (let [fresh (read-content! ks)]
        (locking *cache
          (swap! *cache update source merge
                 (into {} (map (fn [[k v]] [k {:at now, :value v}])) fresh)))
        (doseq [[k pending] owned] (deliver pending {:value (get fresh k)})))
      (catch Throwable error
        (locking *cache
          (doseq [k (keys owned)]
            (swap! *cache update-in [source k] dissoc :pending)))
        ;; Release every shared waiter on failure; later requests can retry.
        (doseq [[_ pending] owned] (deliver pending {:error error}))))))

(defn bundle!
  ([] (bundle! nil))
  ([requested]
   (let [ks (contract/validate-keys requested)
         source (:url (settings))
         now (System/currentTimeMillis)
         {:keys [content owned waiting]} (acquire-sections! source ks now)]
     (load-sections! source owned (filterv #(contains? owned %) ks) now)
     {:version contract/version
      :content (merge content
                      (into {} (map (fn [[k pending]]
                                      (let [{:keys [value error]} @pending]
                                        (when error (throw error))
                                        [k value]))) waiting)
                      (when (= source (:source @*immediate))
                        (select-keys (:content @*immediate) ks)))})))

(defn for-route! [route] (bundle! (contract/keys-for-route route)))

(defn fresh-bundle!
  "A fresh public snapshot for render-cache validation; ordinary browser reads
   retain their section cache. A failed CMS read must not validate stale HTML."
  [requested]
  {:version contract/version
   :content (read-content! (contract/validate-keys requested))})

(defn response [requested]
  (try {:status 200 :headers {"Cache-Control" "public, max-age=120"
                               "X-Content-Source" (if (seq (:url (settings))) "strapi" "seed")} :body (bundle! requested)}
       ;; No upstream exceptions or credentials in browser errors.
       (catch Exception error {:status (or (:status (ex-data error)) 503)
                               :body {:error "Content is temporarily unavailable"}})))

(defn hydration-json [bundle]
  ;; Safe inside a non-executable script element, even for editor-provided text.
  (-> (json/write-str bundle)
      (string/replace "<" "\\u003c")
      (string/replace "&" "\\u0026")
      (string/replace "\u2028" "\\u2028")
      (string/replace "\u2029" "\\u2029")))
