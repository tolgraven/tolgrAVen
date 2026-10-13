(ns tolgraven.components.highlight
  #?(:cljs (:require-macros [tolgraven.macros :refer [register-render-functions!]]))
  #?(:cljs (:require [tolgraven.component.registry]
                     [tolgraven.components.code-block :as implementation])))

#?(:cljs
   (defn <code-block> [code & options]
     (into [implementation/<code-block> code] options)))

#?(:cljs (register-render-functions!))
