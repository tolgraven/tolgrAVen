(ns tolgraven.modules.markdown-editor.code
  "The SDK owns code/language/meta; the normal site component owns all rendered code UI."
  (:require [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as r]
            [tolgraven.components.code-block :as code-block]
            ["@mdxeditor/editor" :as editor]))

(defc ^:private <code-editor> [{:keys [code language meta]}]
  (let [context (editor/useCodeBlockEditorContext)]
    [code-block/<code-block> (or code "")
     {:language language
      :meta meta
      :editable? true
      :on-code-change #(.setCode context %)
      :on-language-change #(.setLanguage context %)
      :on-meta-change #(.setMeta context %)}]))

;; Editor descriptor slots require React component types. The stable reverse
;; adapter renders the same ordinary Reagent code component used by the site.
(def code-editor-react (r/reactify-component (fn [props] [<code-editor> props])))

(def descriptors
  #js [#js {:priority 100, :match (fn [_ _] true), :Editor code-editor-react}])
