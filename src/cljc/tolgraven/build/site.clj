(ns tolgraven.build.site
  "Read the site's capability declaration during build/macro expansion."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def resource "site.edn")
(def overrides-resource "site.overrides.edn")
(defn validate! [value]
  (when-not (and (map? value) (every? #{:modules :pages} (keys value))
                 (map? (:modules value)) (every? keyword? (keys (:modules value)))
                 (every? #(and (map? %) (every? keyword? (keys %))
                                (every? boolean? (vals %))) (vals (:modules value)))
                 (or (nil? (:pages value))
                     (and (map? (:pages value)) (every? keyword? (keys (:pages value)))
                          (every? boolean? (vals (:pages value))))))
    (throw (ex-info "Site config needs boolean module options and page selections" {})))
  (when (some #(= false (get-in value [:modules % :enabled])) [:main :coercion])
    (throw (ex-info "The site shell and route coercion cannot be disabled" {})))
  value)

(defn merge-config [defaults overrides]
  (validate! (merge defaults overrides
                    {:modules (merge-with merge (:modules defaults) (:modules overrides))
                     :pages (merge (:pages defaults) (:pages overrides))})))

(defn settings []
  (merge-config (edn/read-string (slurp (io/resource resource)))
                (if-let [snapshot (io/resource overrides-resource)]
                  (edn/read-string (slurp snapshot)) {:modules {}})))

(defn enabled?
  ([module] (not= false (get-in (settings) [:modules module :enabled])))
  ([module feature] (and (enabled? module)
                        (not= false (get-in (settings) [:modules module feature])))))

(defn track! [env]
  (when (:ns env)
    (let [read! (requiring-resolve 'shadow.resource/slurp-resource)]
      (read! env resource)
      (when (io/resource overrides-resource) (read! env overrides-resource)))))

(defmacro configuration []
  (track! &env)
  (settings))

(defmacro when-enabled [module feature & forms]
  (track! &env)
  (when (if feature (enabled? module feature) (enabled? module))
    `(do ~@forms)))
