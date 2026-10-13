(ns tolgraven.modules.markdown.module
  (:require [tolgraven.components.ui.code :as code]
            [tolgraven.components.code-block :as code-block]))

(def spec
  {:id :markdown
   :styles ["/css/tolgraven/modules/monospace.min.css"
            "/css/tolgraven/modules/markdown.min.css"]
   :view {:code-block #'code-block/<code-block>
          :parse #'code/<parse-markdown-components>}})
