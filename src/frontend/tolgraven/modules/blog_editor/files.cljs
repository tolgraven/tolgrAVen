(ns tolgraven.modules.blog-editor.files
  "Native File filtering shared by picker, paste and drop boundaries."
  (:require [clojure.string :as string]))

(defn image-file? [file]
  (or (string/starts-with? (.-type file) "image/")
      (and (string/blank? (.-type file))
           (boolean (re-find #"(?i)\.(jpe?g|png|webp|avif|svg|gif|heic|heif|tiff?)$" (.-name file))))))

(defn image-files [files]
  (filterv image-file? (array-seq files)))
