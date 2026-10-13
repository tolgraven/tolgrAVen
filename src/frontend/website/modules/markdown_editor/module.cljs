(ns website.modules.markdown-editor.module
  {:bundle/depends-on #{:main :markdown-components :highlight}
   :site/owner :blog-editor}
  (:require [website.modules.markdown-editor.entry :as entry]))

(def spec
  {:id :markdown-editor
   :styles ["/css/tolgraven/modules/markdown-editor.min.css"]
   :view {:editor #'entry/<editor>}})
