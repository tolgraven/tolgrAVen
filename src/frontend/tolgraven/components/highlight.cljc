(ns tolgraven.components.highlight
  #?(:cljs (:require [tolgraven.loader.view :as view]))
  #?(:ssr (:require [tolgraven.modules.highlight.views :as implementation])))

#?(:cljs
   (defn <code-block> [code & options]
     #?(:ssr (into [implementation/<code-block> code] options)
        :default (view/form :highlight :code-block (into [code] options)
                            [:pre [:code code]]))))
