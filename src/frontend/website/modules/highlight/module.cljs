(ns website.modules.highlight.module
  (:require [website.modules.highlight.code-block :as code-block]
            [website.modules.highlight.views :as views]))

(def spec
  {:styles ["/css/website/modules/monospace.min.css"
            "/css/website/modules/markdown.min.css"]
   :view {:code-block #'code-block/<code-block>
          :formatted-code #'views/<formatted-code>}})
