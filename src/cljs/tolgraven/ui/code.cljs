(ns tolgraven.ui.code
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ["react-syntax-highlighter" :default SyntaxHighlighter]
   ["react-syntax-highlighter/dist/esm/styles/hljs" :refer [darcula gruvboxDark]]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/clojure" :as clj-lang]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/javascript" :as js-lang]
   ["react-markdown" :default ReactMarkdown]
   ["remark-gfm" :default remarkGfm]))


(def syntax-highlighter (r/adapt-react-class SyntaxHighlighter))
(def react-markdown (r/adapt-react-class ReactMarkdown))

; (.registerLanguage SyntaxHighlighter "javascript" js-lang)
; (.registerLanguage SyntaxHighlighter "clojure" clj-lang)

(defn code-block 
  "Syntax highlighter component for code blocks"
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

(defn markdown-code-component
  "Custom code component for react-markdown that uses our syntax highlighter"
  [props]
  (let [props'(js->clj props {:keywordize-keys true})
        children (some-> props' :children)
        children' (js->clj {:keywordize-keys true})
        class-name (some-> props' :className)
        ;; Extract language from className (format: "language-javascript")
        language (when class-name
                   (or (second (re-find #"language-(\w+)" class-name))
                       (second (re-find #"(\w+)" class-name))))
        ;; Get the actual code content
        code (cond
              (string? children) children
              (map? children')  (some-> children' :target first :value)
              :else (some-> children .-props .-children))]
    
    ;; Use our syntax highlighter for code blocks, fallback for inline code
    (if (re-find #"\n" code)
      (if language
        [code-block code :language language]
        [:div code])
      [:code code])))

(defn parse-markdown-components
  "Parse markdown into pure React components using react-markdown"
  [md-text]
  [react-markdown
   {:children md-text
    :remarkPlugins #js [remarkGfm]
    :components #js {:code (r/reactify-component markdown-code-component)}}])

