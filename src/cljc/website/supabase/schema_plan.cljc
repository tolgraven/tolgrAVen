(ns website.supabase.schema-plan)

(defn schema-resources [tables modules comments-enabled?]
  (vec (distinct
        (concat (keep :sql tables)
                (mapcat (fn [[_ {:keys [sql sql-without-comments]}]]
                          (if (and sql-without-comments (not comments-enabled?))
                            sql-without-comments sql))
                        (sort-by key modules))))))
