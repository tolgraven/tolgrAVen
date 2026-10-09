(ns tolgraven.modules.highlight.module
  (:require [tolgraven.modules.highlight.views :as views]))

(def spec
  {:id :highlight
   :styles ["/css/tolgraven/modules/monospace.min.css"
            "/css/tolgraven/modules/markdown.min.css"]
   :view {:code-block #'views/<code-block>}})
