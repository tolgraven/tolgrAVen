(ns website.modules.markdown-components.module
  {:bundle/depends-on #{:main :coercion}}
  (:require [website.modules.markdown-components.views :as views]
            [website.loader :as loader]))

(def spec
  {:id :markdown-components
   :install (fn [] (loader/acquire-code! :coercion))
   :styles ["/css/tolgraven/modules/markdown-components.min.css"]
   :view {:render #'views/<render>}})
