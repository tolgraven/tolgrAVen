(ns tolgraven.components.markdown
  #?(:cljs (:require [tolgraven.react :as rf]
                     [tolgraven.render-context :as context]
                     [tolgraven.loader.code :as code]
                     [shadow.lazy :as lazy]))
  #?(:ssr (:require [tolgraven.components.ui.code :as implementation])))

#?(:cljs
   (do
     (defn- module-form [view args]
       ;; This small boundary also serves error displays, so it cannot require
       ;; the loader's UI (which itself renders errors). Acquisition is still
       ;; owned by the shared managed :loader/module subscription.
       (when-not context/*server?* @(rf/subscribe [:loader/module :markdown]))
       (let [loadable (get code/modules :markdown)
             spec (or (when context/*server?* (get context/*modules* :markdown))
                      (when (and loadable (lazy/ready? loadable)) @loadable))]
         (if-let [component (get-in spec [:view view])]
           (into [(if (var? component) @component component)] args)
           [:pre (first args)])))
     (defn <code-block> [code & options]
       #?(:ssr (into [implementation/<code-block> code] options)
          :default (module-form :code-block (into [code] options))))
     (defn <parse-markdown-components> [text & options]
       #?(:ssr (into [implementation/<parse-markdown-components> text] options)
          :default (module-form :parse (into [text] options))))))
