(ns website.modules.markdown-editor.code
  "The SDK owns code/language/meta; Highlight owns the rendered code UI."
  (:require [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.modules.highlight.code-block :as code-block]
            ["@mdxeditor/editor" :as editor]))

(defc ^:private <code-editor> [{:keys [code language meta focusEmitter]}]
  (let [context (editor/useCodeBlockEditorContext)
        *input (r/use-ref nil)
        publish-focus! (editor/usePublisher editor/editorInFocus$)
        on-focus! (r/use-callback
                    (fn [_]
                      (publish-focus! #js {:editorType "codeblock"
                                          :rootNode (.-lexicalNode context)
                                          :editorRef (.-current *input)}))
                    [publish-focus! (.-lexicalNode context)])]
    ;; The SDK selects newly inserted decorator nodes through this native emitter.
    ;; It has one callback slot; clearing that slot releases the owned DOM ref.
    (r/use-effect
      (fn []
        (when focusEmitter
          (.subscribe focusEmitter
            (fn [] (when-let [input (.-current *input)] (.focus input))))
          (fn [] (.subscribe focusEmitter (fn [])))))
      [focusEmitter])
    [code-block/<code-block> (or code "")
     {:language language
      :meta meta
      :editable? true
      :code-ref *input
      :on-code-focus on-focus!
      :on-code-change #(.setCode context %)
      :on-language-change #(.setLanguage context %)
      :on-meta-change #(.setMeta context %)}]))

;; The SDK's descriptor slot requires a React component type. It renders the
;; same Reagent code component as the published page, with native edit callbacks.
(def code-editor-react (r/reactify-component (fn [props] [<code-editor> props])))

(def descriptors
  #js [#js {:priority 100, :match (fn [_ _] true), :Editor code-editor-react}])
