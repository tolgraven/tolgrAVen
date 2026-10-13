(ns website.modules.markdown.module
  (:require [website.modules.markdown.views :as code]))

(def spec
  {:id :markdown
   :styles ["/css/tolgraven/modules/monospace.min.css"
            "/css/tolgraven/modules/markdown.min.css"]
   :view {:code-block #'code/<code-block>
          :parse #'code/<parse-markdown-components>}})
