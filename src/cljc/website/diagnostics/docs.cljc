(ns website.diagnostics.docs
  #?(:dev (:require [website.dev.source :as source])))

(def views #?(:dev {:source source/<page>} :default {}))
