(ns website.modules.markdown-editor.module
  (:require [website.modules.markdown-editor.entry :as entry]))

(def spec
  {:bundle/depends-on #{:main :markdown-components :highlight}
   :site/owner :blog-editor
   :styles ["/css/website/modules/markdown-editor.min.css"]
   :view {:editor #'entry/<editor>}})
