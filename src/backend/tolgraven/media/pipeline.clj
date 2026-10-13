(ns tolgraven.media.pipeline
  "Durable media jobs and complete immutable publication, shared by CMS and uploads."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [mount.core :as mount]
            [tolgraven.config :as config]
            [tolgraven.site :as site]
            [tolgraven.media.image :as image]
            [tolgraven.media.query :as query]
            [tolgraven.media.schema :as schema]
            [tolgraven.media.storage :as storage]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.supabase.auth :as auth]
            [tolgraven.supabase.reader :as reader]
            [tolgraven.supabase.plan :as plan]
            [tolgraven.validation :as validation])
  (:import [java.nio.file Files]
           [java.util UUID]
           [java.util.concurrent Executors ExecutorService TimeUnit]))

(def policy-version "responsive-v2")

(defn enabled? []
  (and (site/enabled? :main :media) (true? (get-in config/env [:media :enabled]))))
(defn configured! []
  (when-not (enabled?) (storage/fail! 503 "Media processing is not configured"))
  (when (validation/explain schema/settings (or (:media config/env) {}))
    (storage/fail! 503 "Media settings are invalid")))

(defn roles! [user]
  (mapv :role (:body (platform/request! :get "auth_roles"
                      {:query-params {"user_id" (str "eq." (auth/profile-id user))
                                      "select" "role"}}))))

(defn author! [user]
  (when-not (some #{"admins" "bloggers"} (roles! user))
    (storage/fail! 403 "Only bloggers and administrators can upload media"))
  (auth/profile-id user))

(defn admin! [user]
  (when-not (some #{"admins"} (roles! user))
    (storage/fail! 403 "Administrator access is required")))

(defn job! [id]
  (or (first (:body (platform/request! :get "site_media"
                     {:query-params {"id" (str "eq." id)
                                     "select" "*"
                                     "limit" 1}})))
      (storage/fail! 404 "Media upload not found")))

(defn response [job]
  (let [value (cond-> {:id (:id job) :status (:status job)}
                (= "ready" (:status job)) (assoc :asset (:asset job))
                (= "failed" (:status job)) (assoc :error "Unable to process this image; try another image or retry later"))]
    (when-let [issues (validation/explain schema/upload-response value)]
      (storage/fail! 503 "Media manifest is unavailable"))
    value))

(defn status! [user id]
  (let [job (job! id)]
    (when-not (or (= (:owner_id job) (auth/profile-id user))
                  (some #{"admins"} (roles! user)))
      (storage/fail! 404 "Media upload not found"))
    (response job)))

(defn queue-bytes!
  "Persist a validated private original before accepting a durable job. Pixel
   decoding belongs to the bounded worker, never the upload/webhook request."
  [bytes {:keys [owner-id source-key source-type source-url metadata]}]
  (configured!)
  (when-not (<= 1 (alength ^bytes bytes) storage/max-source-bytes)
    (storage/fail! 400 "Choose an image smaller than 5 MB"))
  (let [extension (storage/extension! bytes)
        hash (storage/digest bytes)
        object (str "media-originals/" hash "." extension)
        id (storage/digest (.getBytes (str source-key ":" hash ":" policy-version) "UTF-8"))
        job {:id id
             :owner_id owner-id
             :source_key source-key
             :source_type source-type
             :source_url source-url
             :source_object object
             :source_ext extension
             :content_hash hash
             :policy_version policy-version
             :metadata (or metadata {})}]
    (storage/put! object (storage/mime-types extension) bytes)
    (let [queued (try
                   (first (:body (platform/request! :post "rpc/tolgraven_queue_media"
                                   {:content-type :json
                                    :form-params {:p_job job}})))
                   (catch Exception _ (storage/fail! 503 "Media queue is busy or unavailable")))]
      (response queued))))

(defn upload! [user {:keys [tempfile size]}]
  (configured!)
  (let [owner (author! user)]
    (when-not (and tempfile (integer? size) (<= 1 size storage/max-source-bytes))
      (storage/fail! 400 "Choose an image smaller than 5 MB"))
    (with-open [input (io/input-stream tempfile)]
      (let [bytes (storage/limited-bytes! input storage/max-source-bytes)
            hash (storage/digest bytes)]
        (queue-bytes! bytes {:owner-id owner
                            :source-key (str "upload:" owner ":" hash)
                            :source-type "upload"})))))

(defn assets! [sources]
  (if-not (enabled?)
    {}
    (let [queries (query/assets sources)]
      (query/asset-map (plan/rows (reader/read-many! queries))))))

(defn resolve! [src] (if-let [asset (get (assets! [src]) src)] {:asset asset} {}))

(defn library! [user {:keys [offset limit] :or {offset 0 limit 24}}]
  (configured!)
  (when (validation/explain schema/library-query {:offset offset :limit limit})
    (storage/fail! 400 "Invalid media library page"))
  (let [owner (author! user)
        admin? (some #{"admins"} (roles! user))
        rows (:body (platform/request! :get "site_media"
                      {:query-params (cond-> {"status" "eq.ready"
                                             "active" "eq.true"
                                             "select" "asset"
                                             "order" "updated_at.desc,id.asc"
                                             "offset" offset
                                             "limit" limit}
                                       (not admin?)
                                       (assoc "or" (str "(owner_id.eq." owner ",source_type.eq.strapi)")))}))
        value {:assets (reduce (fn [assets {:keys [asset]}]
                                 (if (some #(= (:src %) (:src asset)) assets)
                                   assets
                                   (conj assets asset))) [] rows)
               :next-offset (when (= limit (count rows)) (+ offset limit))}]
    (when (validation/explain schema/library value) (storage/fail! 503 "Media library is unavailable"))
    value))

(defn- rendition! [directory prefix {:keys [file] :as rendition}]
  (when-not (re-matches #"original(?:-[0-9]+w)?\.(?:png|jpg|webp|avif)" (or file ""))
    (storage/fail! 503 "Invalid media rendition"))
  (let [bytes (Files/readAllBytes (.toPath (io/file directory file)))
        parts (string/split file #"\." 2)
        object (str prefix "/" (first parts) "-" (storage/digest bytes) "." (second parts))]
    (storage/put! object (:mime rendition) bytes)
    (-> rendition (dissoc :file) (assoc :src (storage/public-url object)))))

(defn publish! [job directory manifest]
  (let [prefix (str "media-renditions/" (:content_hash job) "/" (:policy_version job))
        renditions {:original (rendition! directory prefix (:original manifest))
                    :webp (mapv #(rendition! directory prefix %) (:webp manifest))
                    :avif (mapv #(rendition! directory prefix %) (:avif manifest))}
        asset (cond-> {:id (:id job)
                       :src (get-in renditions [:original :src])
                       :width (:width manifest)
                       :height (:height manifest)
                       :renditions renditions}
                (:placeholder manifest) (assoc :placeholder (:placeholder manifest))
                (string? (get-in job [:metadata :alt])) (assoc :alt (get-in job [:metadata :alt])))]
    (when (validation/explain schema/asset asset) (storage/fail! 503 "Invalid media manifest"))
    asset))

(defn- finish! [job changes]
  ;; Only the current lease can commit: a delayed/stale worker cannot replace a
  ;; reclaimed job's ready manifest. Immutable partial objects are safe to retry.
  (platform/request! :patch "site_media"
    {:query-params {"id" (str "eq." (:id job))
                    "status" "eq.processing"
                    "active" "eq.true"
                    "lease_owner" (str "eq." (:lease_owner job))}
     :content-type :json
     :headers {"Prefer" "return=minimal"}
     :form-params (merge {:lease_owner nil
                         :lease_until nil
                         :updated_at (str (java.time.Instant/now))} changes)}))

(defn process! [job]
  (try
    (let [source (storage/original! (:source_object job))
          _ (when-not (= (:content_hash job) (storage/digest source))
              (storage/fail! 503 "Original media checksum failed"))
          asset (image/with-renditions! source (:source_ext job) #(publish! job %1 %2))]
      (finish! job {:status "ready"
                    :asset asset
                    :canonical_src (:src asset)
                    :source_url (or (:source_url job) (:src asset))
                    :error nil}))
    (catch Exception _
      (log/warn "Media processing failed" {:id (:id job) :attempt (:attempts job)})
      (finish! job {:status (if (< (:attempts job) 3) "queued" "failed")
                    :error "Media processing failed"
                    :next_attempt (str (.plusSeconds (java.time.Instant/now) (* 15 (:attempts job))))}))))

(defn work! []
  (when-let [job (first (:body (platform/request! :post "rpc/tolgraven_claim_media"
                                {:content-type :json
                                 :form-params {:p_worker (str (UUID/randomUUID))}})))]
    (process! job)))

(mount/defstate worker
  :start (when (enabled?)
           (let [executor (Executors/newSingleThreadScheduledExecutor)]
             (.scheduleWithFixedDelay executor
               ^Runnable (fn [] (try (work!)
                                    (catch Exception _ (log/warn "Media worker temporarily unavailable"))))
               0 2 TimeUnit/SECONDS)
             executor))
  :stop (when worker
          (.shutdownNow ^ExecutorService worker)
          (.awaitTermination ^ExecutorService worker 5 TimeUnit/SECONDS)))

(defn start! [] (when (enabled?) (mount/start #'worker)))
