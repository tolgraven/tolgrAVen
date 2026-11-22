(ns website.diagnostics.docs
  #?(:dev (:require [website.inspector.source :as source])))

(def views #?(:dev {:source source/<page>} :default {}))
