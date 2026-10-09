(ns tolgraven.components.markdown
  #?(:cljs (:require [tolgraven.loader.view :as view]
                     [tolgraven.components.highlight :as highlight]))
  #?(:ssr (:require [tolgraven.components.ui.code :as implementation])))

#?(:cljs
   (do
     (defn <code-block> [code & options]
       (into [highlight/<code-block> code] options))
     (defn <parse-markdown-components> [text & options]
       #?(:ssr (into [implementation/<parse-markdown-components> text] options)
          :default (view/form :markdown :parse (into [text] options) [:pre text])))))
