(ns website.modules.markdown-components.module
  (:require [website.modules.markdown-components.views :as views]
            [website.loader :as loader]))

(def spec
  {:bundle/depends-on #{:main :coercion}
   :install (fn [] (loader/acquire-code! :coercion))
   :view {:render #'views/<render>}})
