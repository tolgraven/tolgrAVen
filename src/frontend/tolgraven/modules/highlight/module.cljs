(ns tolgraven.modules.highlight.module
  (:require [tolgraven.modules.highlight.views :as views]))

(def spec
  {:id :highlight
   :view {:code-block #'views/<code-block>}})
