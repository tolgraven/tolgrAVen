(ns tolgraven.modules.settings.schema
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:panel-open :boolean}))
(def theme (c/optional-map {:dark-mode :boolean, :colorscheme :string}))
