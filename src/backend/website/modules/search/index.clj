(ns website.modules.search.index
  "Module-owned delivery of transactional projections to Typesense."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [website.content.service :as content]
            [website.site-catalog :as catalog]
            [website.platform.supabase :as platform]
            [website.modules.search.model :as model]
            [website.modules.search.typesense :as typesense]
            [website.validation :as validation])
  (:import [java.util UUID]
           [java.security MessageDigest]
           [java.util.concurrent Executors ExecutorService TimeUnit]))

(defn sources []
  (vec (mapcat (comp :search val) (sort-by key catalog/modules))))

(defn rpc! [function parameters]
  (:body (platform/request! :post (str "rpc/" function)
            {:content-type :json :form-params parameters})))

(defn consumer []
  ;; Separate sinks sharing a database must never acknowledge each other's jobs.
  ;; Key rotation keeps the same destination identity; credentials are excluded.
  (let [{:keys [url collection]} (typesense/settings)
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str url "/" collection) "UTF-8"))]
    (str "typesense-" (apply str (map #(format "%02x" (bit-and 255 %)) digest)))))

(defn searchable-text
  "Only authored textual fields from already-public content. Never walk arbitrary
   settings/URLs/tokens and index every string in a CMS response."
  [value]
  (cond
    (map? value) (string/join " " (keep (fn [[key entry]]
                                          (cond
                                            (= :categories key)
                                            (string/join " " (map (fn [[title _ entries]]
                                                                   (string/join " " (cons title entries))) entry))
                                            (and (#{:title :heading :text :body :description :subtitle :caption :alt :artist} key)
                                                 (string? entry)) entry
                                            (and (#{:title :heading :text :body :description :subtitle :caption
                                                    :children :items :sections :content :blocks} key)
                                                 (coll? entry)) (searchable-text entry))) value))
    (sequential? value) (string/join " " (map searchable-text value))
    :else ""))

(defn content-documents [source snapshot]
  (vec (for [section (:content source)
             :let [value (get snapshot section)
                   text (model/plain-text (searchable-text value))]
             :when (not (string/blank? text))]
         {:id (str "site-section-" (name section))
          :kind (:kind source)
          :title (or (when (string? (:title value)) (:title value))
                     (when (string? (:heading value)) (:heading value))
                     (string/capitalize (name section)))
          :text text
          :href (or (:href source) (str "/section/" (name section)))})))

(defn refresh-content! []
  (doseq [source (filter #(seq (:content %)) (sources))]
    (let [snapshot (:content (content/fresh-bundle! (:content source)))
          documents (content-documents source snapshot)
          by-id (into {} (map (juxt :id identity)) documents)]
      ;; A successful empty read removes old text. Failed reads retain it.
      (doseq [section (:content source)
              :let [id (str "site-section-" (name section))]]
        (rpc! "website_search_put" {:p_id id :p_kind (:kind source) :p_document (get by-id id)})))))

(defn initialize! []
  (rpc! "website_search_register" {:p_consumer (consumer) :p_kinds (mapv :kind (sources))})
  (when (typesense/ensure-collection!)
    (rpc! "website_search_replay" {:p_consumer (consumer)})))

(defn index-document [document]
  (-> (validation/check! "Public search document" model/document document)
      (update :text model/plain-text)
      (update :title model/plain-text)))

(defn work! []
  (let [worker (str (UUID/randomUUID))
        jobs (rpc! "website_search_claim" {:p_consumer (consumer) :p_worker worker :p_limit 40})
        documents (vec (keep (fn [{:keys [document]}]
                               (when document
                                 (try (index-document document) (catch Exception _ nil)))) jobs))
        successes (try (typesense/import! documents) (catch Exception _ (repeat false)))
        outcomes (into {} (map (fn [document success?] [(:id document) success?]) documents successes))
        tombstones (mapv :id (remove :document jobs))
        deleted? (try (typesense/delete-many! tombstones) (catch Exception _ false))]
    (doseq [{:keys [id revision document]} jobs]
      (let [success? (if document (get outcomes id false) deleted?)]
        (rpc! "website_search_finish" {:p_consumer (consumer)
                                        :p_worker worker
                                        :p_id id
                                        :p_revision revision
                                        :p_success (boolean success?)})))
    (count jobs)))

(defn start! []
  (when (typesense/configured?)
    (let [executor (Executors/newSingleThreadScheduledExecutor)
          *initialized? (atom false)
          *content-at (atom 0)]
      (.scheduleWithFixedDelay executor
        ^Runnable
        (fn []
          (try
            (when-not @*initialized? (initialize!) (reset! *initialized? true))
            (when (> (- (System/currentTimeMillis) @*content-at) 120000)
              (when (typesense/ensure-collection!)
                (rpc! "website_search_replay" {:p_consumer (consumer)}))
              (reset! *content-at (System/currentTimeMillis))
              (try (refresh-content!)
                   (catch Exception _ (log/warn "Public CMS search refresh pending; retaining the last projection"))))
            (loop [remaining 5]
              (when (and (pos? remaining) (= 40 (work!))) (recur (dec remaining))))
            (catch Exception _
              (reset! *initialized? false)
              (log/warn "Search index delivery pending; retaining durable jobs"))))
        10 5 TimeUnit/SECONDS)
      (fn [] (.shutdownNow ^ExecutorService executor)
             (.awaitTermination ^ExecutorService executor 5 TimeUnit/SECONDS)))))
