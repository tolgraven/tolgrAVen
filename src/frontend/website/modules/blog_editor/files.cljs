(ns website.modules.blog-editor.files
  "Native File filtering shared by picker, paste and drop boundaries."
  (:require [clojure.string :as string]))

(defn image-file? [file]
  (or (string/starts-with? (.-type file) "image/")
      (and (string/blank? (.-type file))
           (boolean (re-find #"(?i)\.(jpe?g|png|webp|avif|svg|gif|heic|heif|tiff?)$" (.-name file))))))

(defn image-files [files]
  (filterv image-file? (array-seq files)))

(defn image-transfer? [transfer]
  ;; OS dragover keeps File contents/names protected until drop. Item MIME
  ;; identifies images; an unknown file MIME is filtered by name on drop.
  (boolean
    (or (seq (image-files (.-files transfer)))
        (some (fn [item]
                (and (= "file" (.-kind item))
                     (or (string/blank? (.-type item))
                         (string/starts-with? (.-type item) "image/"))))
              (array-seq (.-items transfer))))))
