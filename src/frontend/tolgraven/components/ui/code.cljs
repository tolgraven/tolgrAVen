(ns tolgraven.components.ui.code
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [reagent.core :as r]
    [tolgraven.components.highlight :as highlight]
    ["react-markdown$default" :as ReactMarkdown]
    ["remark-gfm$default" :as remarkGfm]
    ["rehype-raw$default" :as rehypeRaw]))


(def react-markdown (r/adapt-react-class ReactMarkdown))
(def omit-markdown-component
  (r/reactify-component (fn [_] nil)))

;; Kept as a public compatibility entry; the full highlighter owns its bundle.
(def <code-block> highlight/<code-block>)

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
