(ns tolgraven.modules.markdown.module
  (:require [tolgraven.components.ui.code :as code]))

(def spec
  {:id :markdown
   :styles ["/css/tolgraven/modules/markdown.min.css"]
   :view {:code-block #'code/<code-block>
          :parse #'code/<parse-markdown-components>}})
