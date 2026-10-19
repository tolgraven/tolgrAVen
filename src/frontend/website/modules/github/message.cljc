(ns website.modules.github.message
  (:require [clojure.string :as string]))

(defn parse-subject
  "Return [subject subcategory category]; bodies and trailers are never tags."
  [message]
  (let [subject (first (string/split-lines (or message "")))
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
    [text'
     (or (some-> scope string/trim not-empty) (when nested? subcategory) category')
     (when (or scope nested?) category')]))

(defn parse-message
  "Keep the stored legacy tuple, including the original body as ordinary text."
  [message]
  (let [[text subtitle title] (parse-subject message)
        body (rest (string/split-lines (or message "")))]
    [(string/join "\n" (cons text body)) subtitle title]))
