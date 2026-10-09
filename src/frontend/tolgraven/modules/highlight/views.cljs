(ns tolgraven.modules.highlight.views
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [reagent.core :as r]
    ["react-syntax-highlighter/dist/esm/default-highlight$default" :as SyntaxHighlighter]
    ["react-syntax-highlighter/dist/esm/styles/hljs/gruvbox-dark$default" :as gruvboxDark]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/clojure" :as clj-lang]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/javascript" :as js-lang]
    ))

(def syntax-highlighter (r/adapt-react-class SyntaxHighlighter))

; (.registerLanguage SyntaxHighlighter "javascript" js-lang)
; (.registerLanguage SyntaxHighlighter "clojure" clj-lang)

(defc <code-block>
  "Full language support is acquired only by code-block consumers."
  [code & {:keys [language style basic?]
           :or {language "clojure"
                basic? true
                style gruvboxDark}}]
  [syntax-highlighter
   {:language language
    :style style
    :showLineNumbers (not basic?)
    :children code
    :wrapLines (not basic?)}])
