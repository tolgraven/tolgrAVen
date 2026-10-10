(ns tolgraven.components.ui.code
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [tolgraven.components.highlight :as highlight]
    [tolgraven.components.code-block :as code-block]
    ["react-markdown$default" :as ReactMarkdown]
    ["remark-gfm$default" :as remarkGfm]
    ["rehype-raw$default" :as rehypeRaw]
    ["unist-util-visit" :refer [visit]]))


(def options-schema
  [:maybe
   [:map
    [:allow-images? {:optional true} :boolean]
    [:allow-raw? {:optional true} :boolean]
    [:default-language {:optional true} [:maybe :string]]
    [:auto-languages {:optional true} [:maybe [:vector :string]]]
    [:code-options {:optional true} [:maybe code-block/options-schema]]]])

(def block-context (rf/create-context false))
(def link-context (rf/create-context false))

(def react-markdown (r/adapt-react-class ReactMarkdown))
(def omit-markdown-component
  (r/reactify-component (fn [_] nil)))

;; Kept as a public compatibility entry; the full highlighter owns its bundle.
(def <code-block> highlight/<code-block>)

(defc <markdown-code-component>
  "Custom code component for react-markdown that uses our syntax highlighter"
  [{:keys [children className default-language auto-languages code-options]}]
  ;; reactify-component supplies a Clojure map. ReactMarkdown's code children
  ;; are text; do not recursively convert React elements or inspect their props.
  (let [code (if (string? children) children "")
        language (some->> className (re-find #"language-([\w-]+)") second)
        block? (rf/use-context block-context)
        linked? (rf/use-context link-context)]
    [<code-block> code
     (merge {:default-language default-language
             :auto-languages auto-languages}
       code-options
       (cond-> {:inline? (not block?)}
         language (assoc :language language)
         linked? (assoc :copy? false)))]))

(defn- mark-markdown-blocks []
  ;; Before rehype-raw, only Markdown code blocks have native pre nodes.
  ;; A private node type survives rehype-raw via passThrough. Authored HTML
  ;; cannot forge that type by setting an attribute.
  (fn [tree]
    (visit tree "element"
      (fn [node]
        (when (= "pre" (.-tagName node))
          (set! (.-type node) "tolgravenCodeBlock")
          nil)))))

(defn- restore-markdown-blocks []
  (fn [tree]
    (visit tree "tolgravenCodeBlock"
      (fn [node]
        (set! (.-type node) "element")
        (set! (.-data node) #js {:tolgravenCodeBlock true})
        nil))))

(def markdown-link-react
  (r/reactify-component
    (fn [{:keys [children] :as props}]
      [:a (dissoc props :node :children)
       [:> (rf/context-provider link-context) {:value true} children]])))

(def markdown-pre-react
  (r/reactify-component
    (fn [{:keys [node children] :as props}]
      (if (true? (some-> node (aget "data") (aget "tolgravenCodeBlock")))
        ;; The generated code component owns its pre. Nested pre/div content
        ;; would make the HTML parser repair SSR markup before hydration.
        [:> (rf/context-provider block-context) {:value true} children]
        ;; Trusted raw pre keeps its own attributes and whitespace semantics.
        [:pre (dissoc props :node :children) children]))))

(defc <parse-markdown-components>
  "Parse markdown into pure React components using react-markdown"
  {:args-schema [:cat [:maybe :string] [:? options-schema]]}
  [md-text & [{:keys [allow-images? allow-raw? default-language auto-languages code-options]
              :or {allow-images? false
                   allow-raw? false}}]]
  (let [code-component (rf/use-memo
                         #(r/reactify-component
                            (fn [props]
                              [<markdown-code-component>
                               (assoc props :default-language default-language
                                            :auto-languages auto-languages
                                            :code-options code-options)]))
                         #js [default-language auto-languages code-options])]
    [react-markdown
     {:children md-text
      :remarkPlugins [remarkGfm]
      :rehypePlugins (cond-> [mark-markdown-blocks]
                       allow-raw? (conj [rehypeRaw {:passThrough ["tolgravenCodeBlock"]}])
                       true (conj restore-markdown-blocks))
      :components (cond-> {:code code-component
                          :pre markdown-pre-react
                          :a markdown-link-react}
                    (not allow-images?) (assoc :img omit-markdown-component))}]))
