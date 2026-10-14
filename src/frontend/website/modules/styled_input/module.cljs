(ns website.modules.styled-input.module
  (:require [website.modules.styled-input.views :as views]))

(def spec
  {:styles ["/css/website/modules/monospace.min.css"
            "/css/website/modules/styled-input.min.css"]
   :view {:input #'views/<input-text-styled>}})
