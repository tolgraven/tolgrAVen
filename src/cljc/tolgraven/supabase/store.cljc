(ns tolgraven.supabase.store
  (:require [tolgraven.supabase.query :as query]))

(defn deep-merge
  [& values]
  (if (every? map? values)
    (apply merge-with deep-merge values)
    (if (every? sequential? values)
      (last values)
      (last values))))

(defn set-document [contract path data merge-fields]
  (when-not (and (= 2 (count path)) (every? query/path-part path) (map? data))
    (throw (ex-info "Expected a collection/document path and map data" {})))
  (let [[collection doc-id] (mapv query/path-part path)
        existing (get-in contract [collection doc-id])]
    (assoc-in contract
              [collection doc-id]
              (if merge-fields
                (deep-merge existing data)
                data))))
