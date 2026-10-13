(ns tolgraven.modules.markdown-components.module
  {:bundle/depends-on #{:main}}
  (:require [tolgraven.modules.markdown-components.views :as views]))

(def spec
  {:id :markdown-components
   :styles ["/css/tolgraven/modules/markdown-components.min.css"]
   :view {:render #'views/<render>}})
