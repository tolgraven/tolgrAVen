(ns website.supabase.schema-plan)

(defn schema-resources [tables modules comments-enabled?]
  (vec (distinct
        (concat (keep :sql tables)
                (mapcat (fn [[_ {:keys [sql sql-without-comments]}]]
                          (if (and sql-without-comments (not comments-enabled?))
                            sql-without-comments sql))
                        (sort-by key modules))
                (when (contains? modules :search)
                  (for [[_ {:keys [search]}] (sort-by key modules)
                        source search
                        :when (and (:sql source)
                                   (or (not= :comments (:feature source)) comments-enabled?))]
                    (:sql source)))))))
