(ns tolgraven.loader.style-catalog
  "Compile-time module CSS inventory, available before any feature JavaScript."
  #?(:clj (:require [tolgraven.build.modules :refer [style-definitions]])
     :cljs (:require-macros [tolgraven.build.modules :refer [style-definitions]])))

(def modules (style-definitions))

(defn bundle-name [module] (str "module-" (name module) ".css"))

(defn dependencies [module]
  (letfn [(visit [id seen]
            (when-not (contains? seen id)
              (concat (mapcat #(visit % (conj seen id))
                              (sort (get-in modules [id :depends-on])))
                      [id])))]
    (vec (distinct (visit module #{})))))

(defn paths [manifest module]
  (vec (distinct (mapcat #(get manifest % []) (dependencies module)))))
