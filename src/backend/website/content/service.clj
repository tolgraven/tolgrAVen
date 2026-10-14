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
(defonce *inflight (atom {}))
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
                             (merge node (select-keys asset [:src :width :height :renditions :placeholder])
                                    {:asset asset, :cms-src (:src node)})
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
  "Publish required shell content atomically. Optional module warmup failures
   keep their last good sections without preventing other modules from loading."
  ([] (refresh-immediate! true))
  ([include-module-content?]
   (let [original @*immediate
         source (:url (settings))
         ks (pages/immediate-keys)
         shell-keys (set contract/shell-content)
         required (filterv shell-keys ks)
         optional (filterv (complement shell-keys) ks)
         previous (when (= source (:source @*immediate)) (:content @*immediate))
         shell (contract/normalize-content (load-content! required))
         modules (when include-module-content?
                   (reduce (fn [content k]
                             (try (merge content (contract/normalize-content (load-content! [k])))
                                  (catch InterruptedException error
                                    (.interrupt (Thread/currentThread))
                                    (throw error))
                                  (catch Exception error
                                    (if (.isInterrupted (Thread/currentThread))
                                      (throw error)
                                      (do (log/warn "Module startup content unavailable" (name k))
                                          content)))))
                           (select-keys previous optional) optional))
         value (merge (select-keys previous ks) shell modules)]
     (when (.isInterrupted (Thread/currentThread))
       (throw (InterruptedException. "Module startup content refresh interrupted")))
     ;; An inline save can finish while the refresh is reading Strapi. Its
     ;; authoritative snapshot must win over this older request.
     (if (compare-and-set! *immediate original
                          (cond-> {:source source, :content value}
                            (and (= source (:source original)) (:edit-revision original))
                            (assoc :edit-revision (:edit-revision original))))
       value (:content @*immediate)))))

(defn install-edited! [sections]
  (let [source (:url (settings))
        now (System/currentTimeMillis)]
    (locking *cache
      (swap! *cache update source
             #(reduce-kv (fn [entries k value] (assoc entries k {:at now, :value value}))
                         (or % {}) sections))
      (swap! *immediate
             (fn [snapshot]
               (if (= source (:source snapshot))
                 (-> snapshot
                     (update :content merge sections)
                     (update :edit-revision (fnil inc 0))) snapshot))))))

(defn edit-revision
  "Server-only cache identity: configured source plus its inline publication count."
  []
  (let [source (:url (settings))
        snapshot @*immediate]
    [source (if (= source (:source snapshot)) (or (:edit-revision snapshot) 0) 0)]))

(defn immediate-content []
  (let [{:keys [source content]} @*immediate]
    (when-not (and content (= source (:url (settings))))
      (throw (ex-info "Page shell has not been initialized" {:status 503})))
    content))

(mount/defstate startup-content
  :start (do
           ;; Only the shared shell gates HTTP readiness. The owned refresh
           ;; worker warms module headings after this initial snapshot.
           (refresh-immediate! false)
           (let [executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)
                 interval (max 1 (long (get-in config/env [:ssr :shell-refresh-seconds] 120)))]
             (.scheduleWithFixedDelay executor
               ^Runnable (fn [] (try (refresh-immediate!)
                                    (catch Exception _ (log/warn "Page shell refresh failed; retaining loaded content"))))
               0 interval java.util.concurrent.TimeUnit/SECONDS)
             executor))
  :stop (when startup-content (.shutdownNow ^java.util.concurrent.ExecutorService startup-content)))

(defn- read-content! [ks]
  (let [warm (when (= (:source @*immediate) (:url (settings))) (:content @*immediate))
        missing (filterv #(not (contains? warm %)) ks)]
    (merge (select-keys warm ks)
           (when (seq missing) (contract/normalize-content (load-content! missing))))))

(defn- acquire-sections! [source ks now]
  ;; Only cache/request ownership is serialized. Disjoint requests cannot wait
  ;; behind CMS I/O. Share identical missing sets, not overlapping bundles:
  ;; a bundle containing a bad section must not reject a healthy subset reader.
  (locking *cache
    (let [cached (get @*cache source {})
          missing (filterv #(> (- now (get-in cached [% :at] 0)) 30000) ks)
          request-key (vec (sort missing))
          pending (get-in @*inflight [source request-key])
          owned? (and (seq missing) (nil? pending))
          pending' (when (seq missing) (or pending (promise)))]
      (when owned? (swap! *inflight assoc-in [source request-key] pending'))
      {:content (into {} (map (fn [k] [k (get-in cached [k :value])])) (remove (set missing) ks))
       :missing missing
       :request-key request-key
       :pending pending'
       :owned? (boolean owned?)})))

(defn bundle!
  ([] (bundle! nil))
  ([requested]
   (let [ks (contract/validate-keys requested)
         source (:url (settings))
         now (System/currentTimeMillis)
         {:keys [content missing request-key pending owned?]} (acquire-sections! source ks now)]
     (when owned?
       (try
         (let [fresh (read-content! missing)]
           (locking *cache
             (swap! *cache update source
                    (fn [cached]
                      (reduce-kv (fn [entries k value]
                                   ;; A newer overlapping read may have finished
                                   ;; first. Do not overwrite its cached snapshot.
                                   (if (>= (get-in entries [k :at] 0) now)
                                     entries (assoc entries k {:at now, :value value})))
                                 (or cached {}) fresh))))
           (deliver pending {:content fresh}))
         (catch Throwable error (deliver pending {:error error}))
         (finally
           (locking *cache
             (swap! *inflight update source dissoc request-key)))))
     (let [result (when pending @pending)]
       (when-let [error (:error result)] (throw error))
       {:version contract/version
        :content (merge content (:content result)
                        (when (= source (:source @*immediate))
                          (select-keys (:content @*immediate) ks)))}))))

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
