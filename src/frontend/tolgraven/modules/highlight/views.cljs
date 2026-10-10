(ns tolgraven.modules.highlight.views
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [tolgraven.modules.highlight.theme :as theme]
    ["lowlight" :as lowlight]
    ["react-syntax-highlighter/dist/esm/default-highlight$default" :as SyntaxHighlighter]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/clojure" :as clj-lang]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/javascript" :as js-lang]
    ))

(def syntax-highlighter (r/adapt-react-class SyntaxHighlighter))

; (.registerLanguage SyntaxHighlighter "javascript" js-lang)
; (.registerLanguage SyntaxHighlighter "clojure" clj-lang)

(defn code-tree
  "Honor language tags; otherwise detect, using the owner's optional fallback
   only when detection produces no language. Unknown tags also try detection."
  [code language default-language & [auto-languages]]
  (let [explicit (when language
                   (try (.highlight lowlight language code)
                        (catch :default _ nil)))
        detected (or explicit (.highlightAuto lowlight code
                                           (when auto-languages
                                             #js {:subset (into-array auto-languages)})))]
    (if (and (nil? (.-language detected)) default-language)
      (.highlight lowlight default-language code)
      detected)))

(defc <code-block>
  "Full language support and Bruvbox are acquired only by code consumers."
  [code & {:keys [language default-language auto-languages style basic? inline? inline-language
                 line-numbers? starting-line-number]
           :or {basic? true
                style theme/bruvbox}}]
  (let [language (or language (when inline? (or inline-language "bash")))
        numbered? (and (not inline?) (if (nil? line-numbers?) (not basic?) line-numbers?))
        tree (rf/use-memo #(code-tree code language default-language auto-languages)
                         #js [code language default-language auto-languages])
        ;; Reuse the selected AST instead of running auto-detection twice.
        generator (rf/use-memo
                    #(clj->js {:listLanguages (fn [] #js [])
                               :highlightAuto (fn [_] tree)})
                    #js [tree])]
    [syntax-highlighter
     (cond-> {:language (or (.-language tree) language "text")
              :astGenerator generator
              :style style
              :showLineNumbers numbered?
              :startingLineNumber (or starting-line-number 1)
              :lineNumberStyle {:userSelect "none"}
              :children code
              :wrapLines numbered?}
       inline? (assoc :PreTag "code"
                      :CodeTag "span"
                      :className "code-highlight"
                      :customStyle {:display "inline"
                                    :padding 0
                                    :background "transparent"}))]))
