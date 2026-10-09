(ns tolgraven.modules.coercion.module
  (:require [tolgraven.validation :as validation]
            [tolgraven.validation.malli :as engine]
            [tolgraven.schema.page-coercion :as page]
            [tolgraven.schema.malli-coercion :as malli]))

(def spec
  {:id :coercion
   :install (fn []
              (validation/install-engine! engine/functions)
              (page/install! malli/coercion))})
