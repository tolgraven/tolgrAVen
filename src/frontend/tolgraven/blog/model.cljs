(ns tolgraven.blog.model
  "Shared normalization for blog views, subscriptions, and navigation events."
  (:require [clojure.string :as string]))

(defn tags [value]
  ;; Stored posts still use whitespace-separated strings. Accept sequences too
  ;; so views and filters share the same contract during a future migration.
  (->> (cond (string? value) (string/split value #"\s+")
             (sequential? value) value
             :else [])
       (filter string?)
       (map string/trim)
       (remove string/blank?)
       distinct
       vec))

(defn page-size [value]
  (if (and (int? value) (pos? value)) value 1))

(defn page-index [number]
  ;; Routes are one-based; app-db stores a zero-based index. Reject partial
  ;; numbers, NaN and negatives instead of letting them reach partitioning.
  (let [number (if (and (string? number) (re-matches #"[0-9]+" number))
                 (js/Number number)
                 number)]
    (if (and (js/Number.isSafeInteger number) (pos? number)) (dec number) 0)))

(defn page-ids [ids index size]
  (when (and (int? index) (<= 0 index) (int? size) (pos? size))
    (->> ids (drop (* index size)) (take size) vec)))

(defn page-count [total size]
  (let [size (page-size size)] (quot (+ total (dec size)) size)))
