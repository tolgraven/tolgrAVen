(ns tolgraven.components.ui.code
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [tolgraven.modules.markdown.schema :as schema]
    [tolgraven.components.highlight :as highlight]
    ["react-markdown$default" :as ReactMarkdown]
    ["remark-gfm$default" :as remarkGfm]
    ["rehype-raw$default" :as rehypeRaw]))


(def block-context (rf/create-context false))

(def react-markdown (r/adapt-react-class ReactMarkdown))
(def omit-markdown-component
  (r/reactify-component (fn [_] nil)))

;; Kept as a public compatibility entry; the full highlighter owns its bundle.
(def <code-block> highlight/<code-block>)

(defc <markdown-code-component>
  "Custom code component for react-markdown that uses our syntax highlighter"
  [{:keys [children className default-language auto-languages]}]
  ;; reactify-component supplies a Clojure map. ReactMarkdown's code children
  ;; are text; do not recursively convert React elements or inspect their props.
  (let [code (if (string? children) children "")
        language (some->> className (re-find #"language-([\w-]+)") second)
        block? (rf/use-context block-context)]
    [<code-block> code
     :language language
     :default-language default-language
     :auto-languages auto-languages
     :inline? (not block?)]))

(def markdown-pre-react
  ;; The code component owns its pre. Nested pre/div content otherwise makes
  ;; the HTML parser repair SSR markup before React can hydrate it.
  (r/reactify-component
    (fn [{:keys [children]}]
      [:> (rf/context-provider block-context) {:value true} children])))

(defc <parse-markdown-components>
  "Parse markdown into pure React components using react-markdown"
  {:args-schema [:cat [:maybe :string] [:? schema/options]]}
  [md-text & [{:keys [allow-images? allow-raw? default-language auto-languages]
              :or {allow-images? false
                   allow-raw? false}}]]
  (let [code-component (rf/use-memo
                         #(r/reactify-component
                            (fn [props]
                              [<markdown-code-component>
                               (assoc props :default-language default-language
                                            :auto-languages auto-languages)]))
                         #js [default-language auto-languages])]
    [react-markdown
     (cond-> {:children md-text
              :remarkPlugins [remarkGfm]
              :components (cond-> {:code code-component
                                   :pre markdown-pre-react}
                            (not allow-images?) (assoc :img omit-markdown-component))}
       allow-raw? (assoc :rehypePlugins [rehypeRaw]))]))
