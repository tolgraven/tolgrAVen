(ns website.modules.highlight.module
  (:require [website.modules.highlight.code-block :as code-block]
            [website.modules.highlight.views :as views]))

(def spec
  {:id :highlight
   :styles ["/css/tolgraven/modules/monospace.min.css"
            "/css/tolgraven/modules/markdown.min.css"]
   :view {:code-block #'code-block/<code-block>
          :formatted-code #'views/<formatted-code>}})
