(ns website.diagnostics.stack
  #?(:dev (:require [website.dev.stack :as dev])))
(defn <stack> [stack]
  #?(:dev [dev/<stack> stack] :default [:pre stack]))
