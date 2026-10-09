(ns tolgraven.modules.styled-input.module
  (:require [tolgraven.modules.styled-input.views :as views]))

(def spec
  {:id :styled-input
   :styles ["/css/tolgraven/modules/monospace.min.css"
            "/css/tolgraven/modules/styled-input.min.css"]
   :view {:input #'views/<input-text-styled>}})
