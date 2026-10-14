(ns website.modules.highlight.views
  (:require
    [website.component.registry]
    [website.component.font :as font]
    [website.macros :refer-macros [defc]]
    [website.react :as r]

    [website.modules.highlight.theme :as theme]
    ["lowlight" :as lowlight]
    ["react-syntax-highlighter/dist/esm/default-highlight$default" :as SyntaxHighlighter]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/clojure" :as clj-lang]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/javascript" :as js-lang]
    ))

(defc <font-pre>
  {:features [[:font font/fira]]}
  [{:keys [children] :as props}]
  [:pre (update (dissoc props :children) :style #(when % (js->clj % :keywordize-keys true))) children])

(defc <font-inline>
  {:features [[:font font/fira]]}
  [{:keys [children] :as props}]
  [:code (update (dissoc props :children) :style #(when % (js->clj % :keywordize-keys true))) children])

;; PreTag is an ordinary React interop boundary. Its children are the native
;; highlighted text tree; duplicating them mounts no highlighter or effects.
(def font-pre (r/reactify-component (fn [props] [<font-pre> props])))
(def font-inline (r/reactify-component (fn [props] [<font-inline> props])))

(def syntax-highlighter (r/adapt-react-class SyntaxHighlighter))

; (.registerLanguage SyntaxHighlighter "javascript" js-lang)
; (.registerLanguage SyntaxHighlighter "clojure" clj-lang)

(def language-aliases
  ;; These fence tags share Clojure's grammar. Authoring retains the original tag.
  {"clj" "clojure"
   "cljs" "clojure"
   "cljc" "clojure"
   "clojurescript" "clojure"})

(defn- grammar-language [language]
  (get language-aliases language language))

(defn code-tree
  "Honor language tags; otherwise detect, using the owner's optional fallback
   only when detection produces no language. Unknown tags also try detection."
  [code language default-language & [auto-languages]]
  (let [language' (grammar-language language)
        default-language' (grammar-language default-language)
        auto-languages' (when auto-languages (vec (distinct (map grammar-language auto-languages))))
        explicit (when language'
                   (try (.highlight lowlight language' code)
                        (catch :default _ nil)))
        detected (or explicit (.highlightAuto lowlight code
                                           (when auto-languages'
                                             #js {:subset (into-array auto-languages')})))]
    (if (and (nil? (.-language detected)) default-language')
      (.highlight lowlight default-language' code)
      detected)))

(defc <formatted-code>
  "Full language support and Bruvbox are acquired only by code consumers."
  [code & {:keys [language default-language auto-languages style basic? inline? inline-language
                 line-numbers? starting-line-number line-props]
           :or {basic? true
                style theme/bruvbox}}]
  (let [language (or language (when inline? (or inline-language "bash")))
        numbered? (and (not inline?) (if (nil? line-numbers?) (not basic?) line-numbers?))
        tree (r/use-memo #(code-tree code language default-language auto-languages)
                         [code language default-language auto-languages])
        ;; Reuse the selected AST instead of running auto-detection twice.
        generator (r/use-memo
                    #(clj->js {:listLanguages (fn [] #js [])
                               :highlightAuto (fn [_] tree)})
                    [tree])]
    [syntax-highlighter
     (cond-> {:language (or (.-language tree) language "text")
              :astGenerator generator
              :style style
              :showLineNumbers numbered?
              :startingLineNumber (or starting-line-number 1)
              :lineNumberStyle {:userSelect "none"}
              :children code
              :PreTag font-pre
              :wrapLines (or numbered? (boolean line-props))}
       line-props (assoc :lineProps line-props)
       inline? (assoc :PreTag font-inline
                      :CodeTag "span"
                      :className "code-highlight"
                      :customStyle {:display "inline"
                                    :padding 0
                                    :background "transparent"}))]))
