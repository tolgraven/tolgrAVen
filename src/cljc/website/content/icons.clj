(ns website.content.icons
  "Icon references from the existing typed content fields, shared by authoring and sync."
  (:require [clojure.string :as string]))

(def references-schema [:set [:re #"^(?:solid|brands)/[a-z0-9-]+$"]])

(defn- timeline-reference [classes]
  (let [tokens (string/split (or classes "") #"\s+")
        glyph (some #(when (and (string/starts-with? % "fa-")
                               (not (#{"fa-solid" "fa-regular" "fa-brands"} %)))
                      (subs % 3)) tokens)]
    (when (seq glyph)
      (str (if (some #{"fab" "fa-brands"} tokens) "brands/" "solid/") glyph))))

(defn references [sections]
  (into #{}
    (concat
      (for [[_ glyph] (get-in sections [:services :categories]) :when (seq glyph)]
        (str "solid/" glyph))
      (keep (comp timeline-reference :icon) (get-in sections [:cv :cv :timeline]))
      (for [item (concat (:footer sections) (:post-footer sections))
            {:keys [icon]} (:links item)
            :when (seq icon)]
        (str "brands/" icon)))))
