(ns tolgraven.diagnostics.issues
  #?(:dev (:require [tolgraven.dev.values :as dev])))
(defn <details> [issues]
  #?(:dev (when-let [details (:dev/issues (meta issues))] [dev/<issues> details])
     :default nil))
