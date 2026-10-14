(ns website.modules.docs.schema
  (:require [website.schema.common :as c]))

(def state (c/optional-map {:current-page :?string, :previous-page :?string}))
