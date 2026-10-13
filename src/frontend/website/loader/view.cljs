(ns website.loader.view
  "Small lazy view boundary usable by bootstrap/error UI without loader UI cycles."
  (:require [website.react :as r]
            [website.render-context :as context]
            [website.loader.code :as code]
            [shadow.lazy :as lazy]))

(defn form [id view args fallback]
  ;; The managed subscription owns acquisition; rendering only reads its result.
  (let [spec (if context/*server?*
               (get context/*modules* id)
               (let [ready? @(r/subscribe [:loader/module id])
                     loadable (get code/modules id)]
                 (when (and ready? loadable (lazy/ready? loadable)) @loadable)))]
    (if-let [component (get-in spec [:view view])]
      (do
        ;; The synchronous renderer owns this inventory. Initial document CSS
        ;; includes exactly these visible exports, including optional owners.
        (when (and context/*server?* context/*rendered-views*)
          (swap! context/*rendered-views* update id (fnil conj #{}) view))
        (into [(if (var? component) @component component)] args))
      fallback)))
