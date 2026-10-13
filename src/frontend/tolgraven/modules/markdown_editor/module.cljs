(ns tolgraven.modules.markdown-editor.module
  {:bundle/depends-on #{:main :markdown-components :highlight}
   :site/owner :blog-editor}
  (:require [tolgraven.modules.markdown-editor.entry :as entry]))

(def spec
  {:id :markdown-editor
   :styles ["/css/tolgraven/modules/markdown-editor.min.css"]
   :view {:editor #'entry/<editor>}})
