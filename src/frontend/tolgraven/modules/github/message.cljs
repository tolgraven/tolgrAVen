(ns tolgraven.modules.github.message
  (:require [clojure.string :as string]))

(defn parse-message
  "Return [text subcategory category]. Only compact subject prefixes are tags;
  colons in prose, URLs and the body remain text. Keep the legacy tuple shape."
  [message]
  (let [[subject & body] (string/split-lines (or message ""))
        subject' (string/trim (or subject ""))
        ;; Bound labels and require whitespace after ':' to avoid parsing URLs.
        prefix #"^([A-Za-z][A-Za-z0-9_./-]{0,31})(?:\(([^():\s]{1,32})\))?(!?):\s+(.+)$"
        [_ category scope breaking text] (re-matches prefix subject')
        [_ subcategory nested-scope nested-breaking nested-text]
        (when (and category (nil? scope)) (re-matches prefix text))
        ;; The personal format allows a second plain category, not recursive
        ;; parsing of arbitrary colons in the description.
        nested? (and subcategory (nil? nested-scope) (string/blank? nested-breaking))
        text' (if category (if nested? nested-text text) subject')
        category' (when category (str category breaking))]
    [(string/join "\n" (cons text' body))
     (or scope (when nested? subcategory) category')
     (when (or scope nested?) category')]))
