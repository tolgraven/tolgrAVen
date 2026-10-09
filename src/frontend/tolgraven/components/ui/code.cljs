(ns tolgraven.components.ui.code
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    ["react-syntax-highlighter/dist/esm/default-highlight$default" :as SyntaxHighlighter]
    ["react-syntax-highlighter/dist/esm/styles/hljs/gruvbox-dark$default" :as gruvboxDark]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/clojure" :as clj-lang]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/javascript" :as js-lang]
    ["react-markdown$default" :as ReactMarkdown]
    ["remark-gfm$default" :as remarkGfm]
    ["rehype-raw$default" :as rehypeRaw]))


(def syntax-highlighter (r/adapt-react-class SyntaxHighlighter))
(def react-markdown (r/adapt-react-class ReactMarkdown))
(def omit-markdown-component
  (r/reactify-component (fn [_] nil)))

; (.registerLanguage SyntaxHighlighter "javascript" js-lang)
; (.registerLanguage SyntaxHighlighter "clojure" clj-lang)

(defc <code-block>
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

(defc <markdown-code-component>
  "Custom code component for react-markdown that uses our syntax highlighter"
  [{:keys [children className]}]
  ;; reactify-component supplies a Clojure map. ReactMarkdown's code children
  ;; are text; do not recursively convert React elements or inspect their props.
  (let [code (if (string? children) children "")
        language (some->> className (re-find #"language-([\w-]+)") second)]
    (if (re-find #"\n" code)
      (if language
        [<code-block> code :language language]
        [:div code])
      [:code code])))

(def markdown-code-react (r/reactify-component (fn [props] [<markdown-code-component> props])))

(defc <parse-markdown-components>
  "Parse markdown into pure React components using react-markdown"
  [md-text & [{:keys [allow-images? allow-raw?]
              :or {allow-images? false
                   allow-raw? false}}]]
  [react-markdown
   (cond-> {:children md-text
           :remarkPlugins [remarkGfm]
           :components (if allow-images?
                         {:code markdown-code-react}
                         {:code markdown-code-react
                              :img omit-markdown-component})}
    allow-raw? (assoc :rehypePlugins [rehypeRaw]))])
