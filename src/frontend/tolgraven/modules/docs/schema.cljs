(ns tolgraven.modules.docs.schema
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:current-page [:maybe :string], :previous-page [:maybe :string]}))
