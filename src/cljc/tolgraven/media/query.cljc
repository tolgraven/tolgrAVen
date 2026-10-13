(ns tolgraven.media.query
  "Public rendition lookups shared by page plans and managed browser bindings.")

(defn assets
  "Query the ready manifest by either its source URL or immutable canonical URL."
  [sources]
  (let [sources' (vec (distinct (filter #(and (string? %) (seq %)) sources)))]
    (if (seq sources')
      (mapv (fn [field]
              {:path-collection [:media-assets]
               :scoped? true
               :order-by [[:updated-at :asc]]
               :where [[field :in sources'] [:status :== "ready"]]})
            [:src :canonical-src])
      [])))

(defn asset-map [rows]
  (into {}
    (mapcat (fn [{:keys [src asset status]}]
              (when (and (= "ready" status) asset)
                (for [url (distinct [src (:src asset)]) :when (seq url)]
                  [url asset]))) rows)))

(defn markdown-sources
  "Inline image sources emitted by the editor; preserve ordinary external URLs
   without granting them trust. Ready manifest lookup decides eligibility."
  [text]
  (vec (distinct (map (fn [[_ angle plain]] (or angle plain))
                     (re-seq #"!\[[^\]]*\]\((?:<([^>]+)>|([^\s)]+))(?:\s+[^)]*)?\)" (or text ""))))))

(defn normalize-row
  "JSON keywordization applies recursively to URL-keyed JSONB too. Restore those
   exact string keys once at each transport boundary, before record validation."
  [row]
  (if (and (map? row) (map? (:image_assets row)))
    (update row :image_assets
      #(into {} (map (fn [[src asset]]
                       [(if (keyword? src) (subs (str src) 1) src) asset])) %))
    row))
