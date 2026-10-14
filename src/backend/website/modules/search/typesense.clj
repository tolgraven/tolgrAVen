(ns website.modules.search.typesense
  "Server-only Typesense transport; credentials never enter browser settings."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [website.config :as config]))

(defn settings []
  {:url (or (:typesense-url config/env) (get-in config/env [:search :url]))
   :key (or (:typesense-api-key config/env) (get-in config/env [:search :api-key]))
   :collection (or (:typesense-collection config/env) (get-in config/env [:search :collection]) "website_v1")})

(defn configured? [] (let [{:keys [url key]} (settings)] (and (seq url) (seq key))))

(defn request! [method path options]
  (let [{:keys [url key]} (settings)]
    (when-not (and (configured?) (re-matches #"https?://[^/?#]+" url))
      (throw (ex-info "Typesense server settings are missing or invalid" {})))
    (let [response (http/request (merge {:method method
                                        :url (str url path)
                                        :headers {"X-TYPESENSE-API-KEY" key}
                                        :conn-timeout 3000
                                        :socket-timeout 15000
                                        :throw-exceptions false
                                        :as :text}
                                       options))]
      (when-not (or (<= 200 (:status response) 299)
                    (and (= method :delete) (= 404 (:status response))))
        ;; Upstream bodies may contain documents or secrets. Keep errors bounded.
        (throw (ex-info "Typesense request failed" {:status (:status response)})))
      response)))

(defn collection-path []
  (let [collection (:collection (settings))]
    (when-not (re-matches #"[A-Za-z0-9_-]{1,80}" collection)
      (throw (ex-info "Invalid Typesense collection" {})))
    (str "/collections/" collection)))

(def collection-fields
  [{:name "kind" :type "string" :facet true}
   {:name "title" :type "string"}
   {:name "text" :type "string"}
   {:name "href" :type "string" :index false}
   {:name "ts" :type "int64" :optional true}
   {:name "repository" :type "string" :facet true :optional true}])

(defn ensure-collection! []
  (let [path (collection-path)]
    (try
      (request! :get path {})
      false
      (catch clojure.lang.ExceptionInfo error
        (if (= 404 (:status (ex-data error)))
          (do (try (request! :post "/collections"
                     {:content-type :json
                      :body (json/write-str {:name (:collection (settings)) :fields collection-fields})})
                   (catch clojure.lang.ExceptionInfo race
                     (when-not (= 409 (:status (ex-data race))) (throw race))))
              true)
          (throw error))))))

(defn import! [documents]
  (if (empty? documents) []
    (let [response (request! :post (str (collection-path) "/documents/import")
                     {:query-params {:action "upsert" :return_id true :dirty_values "reject"}
                      :content-type "text/plain"
                      :body (string/join "\n" (map json/write-str documents))})
          results (mapv #(json/read-str % :key-fn keyword) (string/split-lines (:body response)))]
      ;; HTTP 200 is not success for an import. Never ACK missing/failed rows.
      (mapv (fn [document result]
              (and (true? (:success result)) (= (:id document) (:id result))))
            documents (concat results (repeat nil))))))

(defn delete! [id]
  (request! :delete (str (collection-path) "/documents/"
                         (java.net.URLEncoder/encode id "UTF-8")) {})
  true)

(defn delete-many! [ids]
  (when (seq ids)
    ;; IDs come from canonical producers, never a caller-supplied filter.
    (when-not (every? #(re-matches #"[A-Za-z0-9_-]{1,240}" %) ids)
      (throw (ex-info "Invalid search deletion ID" {})))
    (let [value (json/read-str
                  (:body (request! :delete (str (collection-path) "/documents")
                           {:query-params {:filter_by (str "id:=[" (string/join "," ids) "]")
                                           :batch_size 40}})) :key-fn keyword)]
      (when-not (and (integer? (:num_deleted value)) (<= 0 (:num_deleted value) (count ids)))
        (throw (ex-info "Invalid search deletion response" {})))))
  true)

(defn search! [parameters]
  (json/read-str (:body (request! :get (str (collection-path) "/documents/search")
                         {:query-params parameters})) :key-fn keyword))
