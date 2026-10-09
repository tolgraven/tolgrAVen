(ns tolgraven.components.highlight
  #?(:cljs (:require [tolgraven.components.code-block :as implementation])))

#?(:cljs
   (defn <code-block> [code & options]
     (into [implementation/<code-block> code] options)))
