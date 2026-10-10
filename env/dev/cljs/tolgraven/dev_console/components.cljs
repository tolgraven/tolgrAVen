(ns tolgraven.dev-console.components
  "Component-only hierarchy, combining live instances and loaded declarations.
   Historical placements are hints, never inferred by rendering hidden views.")

(defn tree [active catalog parents]
  (let [active (into {} (remove #(= "tolgraven.dev-console.views" (first (:component (val %))))) active)
        mounted (group-by :component (vals active))
        catalog (into {} (remove #(= "tolgraven.dev-console.views" (first (key %)))) catalog)
        ready (into {} (remove #(contains? mounted (key %))) catalog)
        ready-id (fn [component] [:ready component])
        live (into {} (map (fn [[id instance]]
                            [id (assoc instance :id id :status :mounted
                                       :parent-id (when (contains? active (:parent instance)) (:parent instance)))])) active)
        declarations (into {}
                       (for [[component definition] ready
                             :let [observed (disj (get parents component #{}) nil)
                                   placements (keep (fn [parent]
                                                      (let [instances (get mounted parent)]
                                                        (cond (= 1 (count instances)) (:instance (first instances))
                                                              (contains? ready parent) (ready-id parent)))) observed)
                                   placement (when (= 1 (count placements)) (first placements))]]
                         [(ready-id component) {:id (ready-id component) :component component
                                                :status :ready :observed? (boolean placement)
                                                :definition definition :parent-id placement}]))
        nodes (merge live declarations)
        ;; Historical declaration edges can be recursive. Breaking cycles into
        ;; roots keeps every node reachable without duplicating live instances.
        cycle? (fn [id]
                 (loop [parent (:parent-id (get nodes id)) seen #{id}]
                   (cond (nil? parent) false (contains? seen parent) true
                         :else (recur (:parent-id (get nodes parent)) (conj seen parent)))))
        nodes (into {} (map (fn [[id node]] [id (if (cycle? id) (assoc node :parent-id nil) node)])) nodes)
        children (group-by :parent-id (vals nodes))
        order (fn [nodes] (sort-by #(str (:component %) " " (:key %) " " (:id %)) nodes))]
    (letfn [(branch [node]
              (assoc node :children (mapv branch (order (get children (:id node))))))]
      {:mounted (mapv branch (order (filter #(= :mounted (:status %)) (get children nil))))
       :ready (mapv (fn [[namespace nodes]]
                      {:id [:namespace namespace] :component [namespace] :status :namespace
                       :children (mapv branch (order nodes))})
                    (sort-by key (group-by #(first (:component %))
                                         (filter #(= :ready (:status %)) (get children nil)))))
       :mounted-count (count active) :ready-count (count ready)})))

(defn native-roots
  "Nearest committed native descendants of a logical component, without wrappers."
  [active instance]
  (let [children (group-by :parent (vals active))]
    (loop [pending [instance] seen #{} result []]
      (if-let [id (first pending)]
        (let [record (get active id)
              remaining (subvec pending 1)]
          (cond
            (or (seen id) (nil? record)) (recur remaining (conj seen id) result)
            (:native? record) (recur remaining (conj seen id) (conj result id))
            :else (recur (into remaining (map :instance (get children id)))
                         (conj seen id) result)))
        result))))
