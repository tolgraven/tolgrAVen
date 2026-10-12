(ns tolgraven.component.features
  "Pure, ordered declaration defaults and per-instance overrides.")

(defn entries [features]
  (mapv #(if (keyword? %) [% true] %) features))

(defn- replace-entry [result [id value]]
  (if (some #(= id (first %)) result)
    (mapv #(if (= id (first %)) [id value] %) result)
    (conj result [id value])))

(defn resolve-options
  "Keep declaration order, then append instance additions. Disabled options never
   evaluate their defaults. Nested :features wins over legacy top-level overrides."
  [defaults spec args]
  (let [defaults' (reduce replace-entry [] (entries defaults))
        supplied (reduce replace-entry
                         (mapv (fn [[id value]]
                                 [id (if (contains? spec id)
                                       (let [supplied (get spec id)]
                                         ;; Legacy template shorthand selected the
                                         ;; layout while retaining declared attrs.
                                         (if (and (= :container id) (vector? supplied))
                                           {:form supplied}
                                           supplied))
                                       value)]) defaults')
                         (entries (:features spec)))
        legacy-container? (and (contains? spec :container)
                               (not (some #(= :container (first %)) (entries (:features spec)))))
        default-map (into {} defaults')
        evaluate (fn [value] (if (fn? value) (apply value args) value))]
    (into []
          (keep (fn [[id override]]
                  (when override
                    (let [default (get default-map id)
                          value (cond
                                  (true? override) (if default (evaluate default) true)
                                  (map? override) (let [base (evaluate default)]
                                                    (if (map? base)
                                                      (let [merged (merge base override)]
                                                        (if (and legacy-container? (= :container id))
                                                          (with-meta merged {:container-defaults base})
                                                          merged))
                                                      override))
                                  :else (evaluate override))]
                      (when value [id value])))))
          supplied)))
