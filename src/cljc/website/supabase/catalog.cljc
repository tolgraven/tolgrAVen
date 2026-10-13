(ns website.supabase.catalog
  "Seed table metadata baked from module-owned portable declarations."
  (:require [clojure.string :as string]
            #?(:clj [website.build.modules :refer [storage-definitions]]))
  #?(:cljs (:require-macros [website.build.modules :refer [storage-definitions]])))

(def identifier
  [:and :keyword
   [:fn #(and (nil? (namespace %))
              (boolean (re-matches #"[a-z_][a-z0-9_]*" (name %))))]])

(def table
  [:map {:closed true}
   [:table identifier]
   [:?sql :string]
   [:?seed-key :keyword]
   [:?cache-key :keyword]
   [:?collections [:set :string]]
   [:?user-collections [:set :string]]
   [:?queries [:map-of :string
     [:map
      [:document-key identifier]
      [:fields [:map-of :keyword identifier]]
      [:?batch-field :keyword]
      [:?cache-document-field identifier]
      [:?summary :string]
      [:?reply-count [:map [:select :string] [:parent-field identifier]]]]]]
   [:key [:vector {:min 1} identifier]]
   [:order [:vector {:min 1} [:tuple identifier [:enum :asc :desc]]]]
   [:?select [:vector {:min 1} identifier]]
   [:?jsonb [:set :keyword]]
   [:?references [:set :keyword]]
   [:?feature :keyword]
   [:?public-columns :string]])

(def module-options
  [:map-of :keyword [:map-of :keyword :boolean]])

(def definitions (storage-definitions))

#?(:clj
   (do
(defn selected-tables
  "Absent options preserve defaults. Explicit false disables an owner/feature.
   Dependencies are checked rather than silently enabling disabled owners."
  [definitions options]
  (let [tables (vec (for [[owner declarations] (sort-by key definitions)
                         :let [settings (get options owner)]
                         :when (not= false (:enabled settings))
                         declaration declarations
                         :when (not= false (get settings (:feature declaration)))]
                     (assoc declaration :owner owner)))
        ids (map :table tables)
        seed-keys (keep :seed-key tables)]
    (when-not (and (= (count ids) (count (set ids)))
                   (= (count seed-keys) (count (set seed-keys))))
      (throw (ex-info "Duplicate Supabase table or seed key" {})))
    (doseq [{:keys [table references]} tables
            dependency references
            :when (and (not= table dependency) (not (contains? (set ids) dependency)))]
      (throw (ex-info "Supabase table requires a disabled or missing table"
                      {:table table :dependency dependency})))
    ;; Stable topological order: referenced parents precede dependent rows.
    (loop [pending tables ordered []]
      (if (empty? pending)
        ordered
        (let [known (set (map :table ordered))
              ready (filterv #(every? (conj known (:table %)) (:references %)) pending)]
          (when (empty? ready)
            (throw (ex-info "Cyclic Supabase table dependencies"
                            {:tables (mapv :table pending)})))
          (recur (filterv #(not (contains? (set (map :table ready)) (:table %))) pending)
                 (into ordered ready)))))))

(defn table-config [{:keys [seed-key key order select] :as declaration}]
  (assoc declaration
         :seed-key seed-key
         :on-conflict (string/join "," (map name key))
         :delete-filter (str (name (first key)) "=not.is.null")
         :order (string/join "," (map (fn [[field direction]]
                                       (str (name field) "." (name direction))) order))
         :select (if (seq select) (string/join "," (map name select)) "*")))

))
