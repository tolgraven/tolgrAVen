(ns website.site
  "The capabilities baked into this site's artifacts; runtime cannot add code."
  (:require [website.supabase.catalog :as storage]
            #?(:clj [website.build.site :refer [configuration]]))
  #?(:cljs (:require-macros [website.build.site :refer [configuration]])))

(def config (configuration))
(defn enabled?
  ([module] (not= false (get-in config [:modules module :enabled])))
  ([module feature] (and (enabled? module)
                        (not= false (get-in config [:modules module feature])))))

(defn routes [declarations]
  (letfn [(select-route [[path & body]]
            (let [data (when (map? (first body)) (first body))
                  children (if data (rest body) body)]
              (when-not (= false (get-in config [:pages (:name data)]))
                (let [selected (keep select-route children)]
                  (when (or data (seq selected) (empty? body))
                    (into (cond-> [path] data (conj data)) selected))))))]
    (vec (keep select-route declarations))))

(def uses-supabase? (boolean (seq (mapcat val storage/definitions))))
