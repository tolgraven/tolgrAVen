(ns tolgraven.modules.highlight.theme
  "Bruvbox's dark palette mapped to Highlight.js token categories.
   Source: tolgraven/bruvbox, dd115f1133ce40f83711584fad51d83796de6898,
   colors/bruvbox.vim (MIT/X11; derived from morhetz/gruvbox).")

(def palette
  {:background "#262525"
   :foreground "#f0e3ba"
   :symbol "#faecc9"
   :identifier "#d5c4a1"
   :comment "#908682"
   :red "#d18479"
   :green "#b4b88d"
   :yellow "#dabd7a"
   :type "#d9c894"
   :blue "#7692ab"
   :keyword "#80a0b3"
   :purple "#bd979d"
   :aqua "#91b8a4"
   :orange "#cca687"
   :function "#dba184"
   :addition "#1f281c"
   :deletion "#281913"})

(def bruvbox
  ;; Conversion happens once at the React highlighter boundary, in its lazy bundle.
  (clj->js
    {"hljs" {:display "block"
             :overflowX "auto"
             :padding "1em"
             :background (:background palette)
             :color (:foreground palette)}
     "hljs-comment" {:color (:comment palette) :fontStyle "italic"}
     "hljs-quote" {:color (:comment palette) :fontStyle "italic"}
     "hljs-keyword" {:color (:keyword palette)}
     "hljs-selector-tag" {:color (:blue palette)}
     "hljs-name" {:color (:symbol palette)}
     "hljs-variable" {:color (:identifier palette)}
     "hljs-params" {:color (:identifier palette)}
     "hljs-subst" {:color (:identifier palette)}
     "hljs-title" {:color (:function palette)}
     "hljs-built_in" {:color (:orange palette)}
     "hljs-builtin-name" {:color (:orange palette)}
     "hljs-string" {:color (:green palette)}
     "hljs-literal" {:color (:red palette)}
     "hljs-number" {:color (:purple palette)}
     "hljs-type" {:color (:type palette)}
     "hljs-class" {:color (:yellow palette)}
     "hljs-symbol" {:color (:blue palette)}
     "hljs-regexp" {:color (:aqua palette)}
     "hljs-meta" {:color (:aqua palette)}
     "hljs-meta-keyword" {:color (:aqua palette) :fontWeight "bold"}
     "hljs-meta-string" {:color (:green palette)}
     "hljs-attr" {:color (:blue palette)}
     "hljs-attribute" {:color (:aqua palette)}
     "hljs-tag" {:color (:blue palette)}
     "hljs-selector-id" {:color (:blue palette)}
     "hljs-selector-class" {:color (:orange palette)}
     "hljs-selector-attr" {:color (:yellow palette)}
     "hljs-selector-pseudo" {:color (:keyword palette)}
     "hljs-template-tag" {:color (:aqua palette)}
     "hljs-template-variable" {:color (:identifier palette)}
     "hljs-section" {:color (:function palette) :fontWeight "bold"}
     "hljs-bullet" {:color (:purple palette)}
     "hljs-link" {:color (:aqua palette) :textDecoration "underline"}
     "hljs-addition" {:background (:addition palette)}
     "hljs-deletion" {:background (:deletion palette)}
     "hljs-emphasis" {:fontStyle "italic"}
     "hljs-strong" {:fontWeight "bold"}}))
