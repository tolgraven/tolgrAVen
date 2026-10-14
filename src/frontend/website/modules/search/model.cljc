(ns website.modules.search.model
  "The public search boundary: plain text, typed navigation, and safe marks."
  (:require [clojure.string :as string]))

(def document-id [:re "^[A-Za-z0-9_-]{1,240}$"])

(def document-fields [:id :kind :title :text :href :thread_href :ts :user :author :tags :repository])

(def document
  [:map {:closed true}
   [:id document-id]
   [:kind :string]
   [:title :string]
   [:text :string]
   [:href [:re "^/(?!/)[^\\s]*$"]]
   [:?thread_href [:re "^/blog/comment/[A-Za-z0-9_-]+$"]]
   [:?ts :int]
   [:?user :?string]
   [:?author :string]
   [:?tags :string]
   [:?repository :string]])

(def segment [:map [:text :string] [:marked? :boolean]])
(def hit [:map [:document document]
          [:preview [:and :keyword [:fn qualified-keyword?]]]
          [:title [:vector segment]]
          [:excerpt [:vector segment]]])
(def result [:map [:query :string]
             [:found [:int {:min 0}]]
             [:page [:int {:min 1}]]
             [:hits [:vector hit]]
             [:suggestions [:vector :string]]])

(defn marked-segments
  "Only Typesense's exact mark delimiter has meaning. All other content stays
   text and is escaped by React, including author-supplied HTML and scripts."
  [text]
  (loop [remaining (or text ""), output []]
      (if-let [index (string/index-of remaining "<mark>")]
        (let [prefix (subs remaining 0 index)
              suffix (subs remaining (+ index 6))
              end (string/index-of suffix "</mark>")]
          (if end
            (recur (subs suffix (+ end 7))
                   (conj (cond-> output (seq prefix) (conj {:text prefix :marked? false}))
                         {:text (subs suffix 0 end) :marked? true}))
            (conj output {:text remaining :marked? false})))
        (cond-> output (seq remaining) (conj {:text remaining :marked? false})))))

(defn plain-text [value]
  ;; Index Markdown as readable text. Preview rendering never interprets HTML.
  (-> (or value "")
      (string/replace #"<!--[\s\S]*?-->" " ")
      (string/replace #"</?[A-Za-z][^>]*>|<![^>]*>" " ")
      (string/replace #"!?\[([^\]]+)\]\([^)]*\)" "$1")
      (string/replace #"(^|\n)\s*[#>]+\s*" "$1")
      (string/replace #"[`*_]" "")
      (string/replace #"\s+" " ")
      string/trim))

(defn completion [query title]
  (let [query' (string/lower-case (string/trim query))
        title' (plain-text title)]
    (when (and (>= (count query') 2)
               (<= (count title') 160)
               (string/includes? (string/lower-case title') query')
               (not= (string/lower-case title') query'))
      title')))
