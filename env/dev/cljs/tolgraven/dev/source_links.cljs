(ns tolgraven.dev.source-links (:require [clojure.string :as string]))
(def catalog-path [:dev-console :source-catalog])
(def catalog-resource
  {:source :url
   :url "/api/dev/source-catalog"
   :into catalog-path})
(defn source-url [file line]
  (str "/docs/source/" (string/join "/" (map js/encodeURIComponent (string/split file #"/")))
       (when (and (number? line) (pos? line)) (str "#L" line))))
(defn resolve-file [catalog file]
  (when (string? file)
    (or (some #(when (= file (:path %)) (:path %)) catalog)
        (let [matches (filterv #(or (= file (:namespace %))
                                   (string/ends-with? file (str "/" (:path %)))
                                   (string/ends-with? (:path %) (str "/" file))) catalog)]
          (when (= 1 (count matches)) (:path (first matches)))))))
