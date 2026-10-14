(ns website.modules.search.api
  (:require [clojure.string :as string]
            [website.schema.http :as schemas]
            [website.modules.search.index :as index]
            [website.modules.search.model :as model]
            [website.modules.search.typesense :as typesense]
            [website.validation :as validation]))

(defn public-hit [sources {:keys [document highlights]}]
  (when-let [source (some #(when (= (:kind %) (:kind document)) %) sources)]
    (let [by-field (into {} (map (juxt :field :snippet)) highlights)]
      {:document (-> (select-keys document model/document-fields)
                     (update :text #(subs % 0 (min 260 (count %)))))
       :preview (:preview source)
       :title (model/marked-segments (or (get by-field "title") (:title document)))
       :excerpt (model/marked-segments (or (get by-field "text") (subs (:text document) 0 (min 260 (count (:text document))))))})))

(defn source-filter! [{:keys [kind filter]}]
  (when-not (re-matches #"[a-z-]+" kind)
    (throw (ex-info "Invalid search source kind" {})))
  (if filter
    ;; A source whose current public scope cannot be established is omitted.
    ;; Its outage must not take down unrelated, already-public search kinds.
    (when-let [scope (try ((requiring-resolve filter)) (catch Exception _ nil))]
      (str "(kind:=" kind " && " scope ")"))
    (str "kind:=" kind)))

(defn response! [{:keys [q page per_page]}]
  (try
    (let [sources (index/sources)
          filters (vec (keep source-filter! sources))
          value (if (empty? filters) {:found 0 :hits []}
                    (typesense/search! {:q q
                                        :query_by "title,text"
                                        :query_by_weights "4,1"
                                        :filter_by (string/join " || " filters)
                                        :highlight_fields "title,text"
                                        :highlight_start_tag "<mark>"
                                        :highlight_end_tag "</mark>"
                                        :snippet_threshold 30
                                        :page (or page 1)
                                        :per_page (or per_page 12)}))
          hits (vec (keep #(public-hit sources %) (:hits value)))
          result {:query q
                  :found (:found value)
                  :page (or page 1)
                  :hits hits
                  :suggestions (vec (take 6 (distinct (keep #(model/completion q (get-in % [:document :title])) hits))))}]
      {:status 200
       ;; Every request must pass the current source visibility gates, including
       ;; repeated queries after a repository or content source becomes private.
       :headers {"Cache-Control" "no-store"}
       :body (validation/check! "Public search response" model/result result)})
    (catch Exception _ {:status 503
                        :headers {"Cache-Control" "no-store"}
                        :body {:error "Search is temporarily unavailable"}})))

(defn routes []
  [["/integrations/search" {:parameters {:query schemas/search-query}
                            :get (fn [request] (response! (get-in request [:parameters :query])))}]])
