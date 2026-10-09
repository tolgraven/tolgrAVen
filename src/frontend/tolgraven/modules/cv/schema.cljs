(ns tolgraven.modules.cv.schema
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:visited :boolean}))
