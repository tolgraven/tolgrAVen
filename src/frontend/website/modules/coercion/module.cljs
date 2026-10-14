(ns website.modules.coercion.module
  (:require [website.validation :as validation]
            [website.validation.malli :as engine]
            [website.schema.page-coercion :as page]
            [website.schema.malli-coercion :as malli]))

(def spec
  {:install (fn []
              (validation/install-engine! engine/functions)
              (page/install! malli/coercion))})
