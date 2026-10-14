(ns website.modules.markdown-editor.module
  (:require [website.modules.markdown-editor.entry :as entry]))

;; Reading and editing share the Markdown parser. Give it one lazy ancestor
;; instead of letting Shadow hoist those packages into their common main.
(def spec
  {:bundle/depends-on #{:main :markdown :markdown-components :highlight}
   :site/owner :blog-editor
   :styles ["/css/website/modules/markdown-editor.min.css"]
   :view {:editor #'entry/<editor>}})
