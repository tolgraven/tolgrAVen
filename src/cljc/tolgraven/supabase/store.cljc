(ns tolgraven.supabase.store)

(defn deep-merge
  [& values]
  (if (every? map? values)
    (apply merge-with deep-merge values)
    (if (every? sequential? values)
      (last values)
      (last values))))

(defn set-document [contract path data merge-fields]
  (let [[collection doc-id] path
        existing (get-in contract [collection doc-id])]
    (assoc-in contract
              [collection doc-id]
              (if merge-fields
                (deep-merge existing data)
                data))))
