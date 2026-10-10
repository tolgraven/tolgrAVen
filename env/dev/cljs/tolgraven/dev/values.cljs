(ns tolgraven.dev.values
  "Bounded development details; public reports still omit values.")

(defn sensitive? [key]
  (boolean (re-find #"(?i)password|secret|token|authorization|cookie|credential|api[-_]?key" (str key))))
(defn value-type [value]
  (cond (nil? value) "nil" (boolean? value) "boolean" (number? value) "number"
        (keyword? value) "keyword" (symbol? value) "symbol" (string? value) "string"
        (map? value) "map" (vector? value) "vector" (set? value) "set"
        (sequential? value) "list" (fn? value) "function" :else "object"))
(defn safe-value
  ([value] (safe-value value 0))
  ([value depth]
   (cond
     (> depth 4) :debug/elided
     (string? value) (subs value 0 (min 1000 (count value)))
     (map? value) (into {} (map (fn [[k v]] [k (if (sensitive? k) :debug/redacted (safe-value v (inc depth)))])) (take 20 value))
     (sequential? value) (mapv #(safe-value % (inc depth)) (take 20 value))
     (set? value) (into #{} (map #(safe-value % (inc depth))) (take 20 value))
     (#{"nil" "boolean" "number" "keyword" "symbol"} (value-type value)) value
     :else (keyword "debug" (value-type value)))))
(defn issue [error required]
  {:path (vec (:in error))
   :actual-type (value-type (:value error))
   :actual (if (some sensitive? (:in error)) :debug/redacted (safe-value (:value error)))
   :required (safe-value required)})
(declare <value>)
(defn <sequence> [open close items]
  [:<> open
   (for [[index item] (map-indexed vector items)]
     ^{:key index} [:<> (when (pos? index) " ") [<value> item]])
   close])
(defn <value> [value]
  [:span {:class (str "dev-value--" (value-type value))}
   (cond
     (map? value) [<sequence> "{" "}" (mapcat identity value)]
     (vector? value) [<sequence> "[" "]" value]
     (set? value) [<sequence> "#{" "}" value]
     (sequential? value) [<sequence> "(" ")" value]
     :else (pr-str value))])
(defn <issues> [issues]
  [:div.dev-validation
   (for [[index {:keys [path actual actual-type required]}] (map-indexed vector issues)]
     ^{:key index}
     [:article.dev-validation__issue
      [:h4 [<value> path]]
      [:dl [:dt "Required"] [:dd [:code [<value> required]]]
       [:dt "Received"] [:dd [:span.dev-validation__type actual-type] " " [:code [<value> actual]]]]])])
