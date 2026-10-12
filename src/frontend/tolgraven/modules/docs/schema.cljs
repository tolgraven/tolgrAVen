(ns tolgraven.modules.docs.schema
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:current-page :?string, :previous-page :?string}))
