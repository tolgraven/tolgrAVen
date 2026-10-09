(ns tolgraven.diagnostics.routes
  #?(:dev (:require [reitit.dev.pretty :as pretty])))

(def options #?(:dev {:exception pretty/exception} :default {}))
