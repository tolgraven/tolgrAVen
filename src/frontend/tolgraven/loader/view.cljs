(ns tolgraven.loader.view
  "Small lazy view boundary usable by bootstrap/error UI without loader UI cycles."
  (:require [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            [tolgraven.loader.code :as code]
            [shadow.lazy :as lazy]))

(defn form [id view args fallback]
  ;; The managed subscription owns acquisition; rendering only reads its result.
  (when-not context/*server?* @(rf/subscribe [:loader/module id]))
  (let [loadable (get code/modules id)
        spec (or (when context/*server?* (get context/*modules* id))
                 (when (and loadable (lazy/ready? loadable)) @loadable))]
    (if-let [component (get-in spec [:view view])]
      (into [(if (var? component) @component component)] args)
      fallback)))
