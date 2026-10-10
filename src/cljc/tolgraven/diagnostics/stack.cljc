(ns tolgraven.diagnostics.stack
  #?(:dev (:require [tolgraven.dev.stack :as dev])))
(defn <stack> [stack]
  #?(:dev [dev/<stack> stack] :default [:pre stack]))
