(ns tolgraven.modules.markdown-editor.upload-tree
  "Editor-only upload nodes are inserted into a parsed tree, never authored source."
  (:require [tolgraven.markdown.authoring :as authoring]))

(def upload-name "editor-upload")
(def image-component "tolgraven.components.content.markdown/<image>")
(def layout-keys [:layout-width :layout-height :layout-align :layout-flow])

(defn exported-image [attributes]
  (when (seq (:src attributes))
    (if (some #(contains? attributes %) layout-keys)
      {:type "mdxJsxTextElement"
       :name "Component"
       :attributes (authoring/node-attributes
                     {:name image-component
                      :arguments (into {} (map (fn [[key value]] [key {:literal value}]))
                                       (select-keys attributes (into [:src :alt :title] layout-keys)))})
       :children []}
      (cond-> {:type "image", :url (:src attributes), :alt (or (:alt attributes) "")}
        (:title attributes) (assoc :title (:title attributes))))))

(defn- image-attributes [node]
  (if (= "image" (:type node))
    (cond-> {:src (:url node), :alt (or (:alt node) "")}
      (:title node) (assoc :title (:title node)))
    (when (= image-component (authoring/component-name node))
      (let [arguments (:arguments (authoring/invocation node))]
        ;; Only literal images use this descriptor. Data-bound site components
        ;; retain the normal component editor and its dependency acquisition.
        (when (and (seq arguments) (every? #(contains? % :literal) (vals arguments)))
          (into {} (map (fn [[key value]] [key (:literal value)])) arguments))))))

(defn upload-node? [node]
  (and (= "textDirective" (:type node)) (= upload-name (:name node))))

(defn upload-node [id position]
  {:type "textDirective"
   :name upload-name
   :data {:editorMedia true}
   :attributes {:id id}
   :children []
   :position {:start {:offset position}, :end {:offset position}}})

(defn import-images
  "Ordinary Markdown images use the same native media node as completed uploads.
   Upload association is editor-only; canonical export contains only image data."
  [node uploads]
  (if-let [attributes (image-attributes node)]
    (let [upload (some #(when (and (:image-inserted? %) (= (:src attributes) (get-in % [:asset :src]))
                                  (= (:position %) (get-in node [:position :start :offset]))) %) uploads)]
      {:type "textDirective"
       :name upload-name
       :data {:editorMedia true}
       :attributes (cond-> attributes
                     upload (assoc :id (:id upload)))
       :children []
       :position (:position node)})
    (cond-> node
      (vector? (:children node)) (update :children #(mapv (fn [child] (import-images child uploads)) %)))))

(defn- start [node] (get-in node [:position :start :offset] 0))
(defn- end [node] (get-in node [:position :end :offset] (start node)))

(declare insert-at)

(defn- insert-children [node id position value-offset]
  (let [children (:children node)
        index (first (keep-indexed (fn [index child]
                                    (when (<= (start child) position (end child)) index)) children))
        insertion (upload-node id position)]
    (if (some? index)
      (let [child (nth children index)
            replacement (insert-at child id position value-offset)]
        (assoc node :children (into (into (subvec children 0 index) replacement) (subvec children (inc index)))))
      (let [index' (count (take-while #(<= (end %) position) children))
            insertion' (if (= "root" (:type node))
                         {:type "paragraph", :children [insertion], :position (:position insertion)}
                         insertion)]
        (assoc node :children (into (conj (subvec children 0 index') insertion') (subvec children index')))))))

(defn insert-at
  "Use parser source positions, preserving the surrounding formatting tree.
   Opaque blocks receive a neighboring chip; the source editor retains its exact point."
  [node id position value-offset]
  (cond
    (upload-node? node) [(upload-node id position) node]

    (= "text" (:type node))
    (let [offset (max 0 (min (count (:value node)) (value-offset node position)))
          value (:value node)]
      (cond-> []
        (pos? offset) (conj (-> node (assoc :value (subs value 0 offset))
                               (assoc-in [:position :end :offset] position)))
        true (conj (upload-node id position))
        (< offset (count value)) (conj (-> node (assoc :value (subs value offset))
                                         (assoc-in [:position :start :offset] position)))))

    (vector? (:children node)) [(insert-children node id position value-offset)]
    (<= position (start node)) [(upload-node id position) node]
    :else [node (upload-node id position)]))

(defn insert-uploads [tree positions value-offset]
  (reduce (fn [tree' [id position]]
            (first (insert-at tree' id position value-offset)))
          tree (reverse (sort-by second positions))))

(defn before-upload
  "Return the pure partial Markdown tree before a native upload node. The caller
   serializes this prefix to locate the insertion point in canonical Markdown."
  [node id]
  (cond
    (and (upload-node? node) (= id (get-in node [:attributes :id]))) {:found? true}
    (and (upload-node? node) (get-in node [:attributes :src]))
    {:found? false, :node (exported-image (:attributes node))}
    (upload-node? node) {:found? false}
    (vector? (:children node))
    (loop [remaining (:children node), previous []]
      (if-let [child (first remaining)]
        (let [{child' :node, found? :found?} (before-upload child id)
              previous' (cond-> previous child' (conj child'))]
          (if found?
            {:found? true, :node (assoc node :children previous')}
            (recur (subvec remaining 1) previous')))
        {:found? false, :node (assoc node :children previous)}))
    :else {:found? false, :node node}))

(defn upload-ids [tree]
  (keep #(when (upload-node? %) (get-in % [:attributes :id]))
        (tree-seq #(vector? (:children %)) :children tree)))
