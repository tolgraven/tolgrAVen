(ns website.modules.markdown.module
  (:require [website.modules.markdown.views :as views]))

(def spec
  {:id :markdown
   :server {:requires #{:highlight}}
   :styles ["/css/website/modules/monospace.min.css"
            "/css/website/modules/markdown.min.css"]
   :view {:parse #'views/<parse-markdown-components>}})
