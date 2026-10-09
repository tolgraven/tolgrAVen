(ns tolgraven.loader.style-catalog
  "Compile-time module CSS inventory, available before any feature JavaScript."
  (:require [clojure.string :as string]
            #?(:clj [tolgraven.build.modules :refer [style-definitions]]))
  #?(:cljs (:require-macros [tolgraven.build.modules :refer [style-definitions]])))

(def modules (style-definitions))

(defn bundle-name [module] (str "module-" (name module) ".css"))

(defn stylesheet-bundle [path]
  ;; Each declared output is fingerprinted once, even when several modules own
  ;; the same dependency. Preserve ordinary module-<feature>.css names.
  (str "module-"
       (-> path
           (string/replace #"^/css/tolgraven/modules/" "")
           (string/replace #"(?:\.min)?\.css$" "")
           (string/replace #"[^A-Za-z0-9_-]" "-"))
       ".css"))

(defn bundle-names [module]
  (mapv stylesheet-bundle (get-in modules [module :paths])))

(def stylesheet-bundles
  (reduce (fn [result path]
            (let [bundle (stylesheet-bundle path)]
              (when-let [previous (get result bundle)]
                (when-not (= [path] previous)
                  (throw (ex-info "Stylesheet bundle names collide" {:bundle bundle}))))
              (assoc result bundle [path])))
          {} (distinct (mapcat :paths (vals modules)))))

(defn dependencies [module]
  (letfn [(visit [id seen]
            (when-not (contains? seen id)
              (concat (mapcat #(visit % (conj seen id))
                              (sort (get-in modules [id :depends-on])))
                      [id])))]
    (vec (distinct (visit module #{})))))

(defn paths [manifest module]
  (vec (distinct (mapcat #(get manifest % []) (dependencies module)))))
