(ns website.modules.markdown.module
  (:require [website.modules.markdown.code-block :as code-block]
            [website.modules.markdown.views :as views]))

(def spec
  {:id :markdown
   :styles ["/css/tolgraven/modules/monospace.min.css"
            "/css/tolgraven/modules/markdown.min.css"]
   :view {:code-block #'code-block/<code-block>
          :parse #'views/<parse-markdown-components>}})
