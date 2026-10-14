(ns website.modules.cv.schema
  (:require [website.schema.common :as c]))

(def state (c/optional-map {:visited :boolean}))
