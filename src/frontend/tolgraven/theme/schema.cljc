(ns tolgraven.theme.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def options (c/optional-map {:dark-mode :boolean, :colorscheme :string}))
