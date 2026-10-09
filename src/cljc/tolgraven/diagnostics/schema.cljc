(ns tolgraven.diagnostics.schema
  "Development contracts are absent from production and Node rendering graphs."
  #?(:dev (:require [tolgraven.dev-console.schema :as console])))

;; Persisted debug settings can still exist in a production state envelope.
(def debug-state #?(:dev console/debug-state :default :map))
(def options #?(:dev console/options :default :map))
(def state #?(:dev console/state :default :map))
