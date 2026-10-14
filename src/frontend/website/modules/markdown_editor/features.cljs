(ns website.modules.markdown-editor.features)

(def available
  #{:history :formatting :headings :lists :quotes :links :images :tables
    :thematic-breaks :code :site-components})

(def schema [:set (into [:enum] (sort available))])
(def post-features available)
(def comment-features (disj post-features :site-components))

(defn enabled [configured]
  (if (nil? configured) post-features configured))
