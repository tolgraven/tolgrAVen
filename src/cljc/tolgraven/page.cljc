(ns tolgraven.page
  "Pure helpers for module-local page specs. No registry or global page list."
  (:require [tolgraven.content.contract :as content]))

(defn dependencies [{:keys [kind module depends]}]
  (into (vec depends)
        (if (= :landing kind)
          [{:source :strapi :keys (content/keys-for-route :home)}]
          [{:source :strapi
            :keys (vec (distinct (concat content/shell-content
                                        (get content/module-content module []))))}])))

(defn- parameter-value [type value]
  (case type
    :page-number (when (and value (re-matches #"[1-9]\d{0,5}" value))
                   #?(:clj (Long/parseLong value) :cljs (js/parseInt value 10)))
    :trailing-id (when-let [[_ id] (and value (re-find #"(?:^|-)(\d{1,15})$" value))]
                   #?(:clj (Long/parseLong id) :cljs (js/parseInt id 10)))
    :document (when (and value (re-matches #"[A-Za-z0-9_.-]+" value) (not= value "..")) value)
    value))

(defn selection [{:keys [data path-params]}]
  (when data
    (reduce-kv
     (fn [result key {:keys [from type]}]
       (if-some [value (parameter-value type (get path-params from))]
         (assoc result key value) (reduced nil)))
     (or (:selection data) (when-let [kind (:kind data)] {:kind kind}) {})
     (:ssr-parameters data {}))))

(defn immediate-keys [spec]
  (->> (dependencies spec)
       (filter #(and (= :strapi (:source %)) (= :startup (:availability %))))
       (mapcat :keys) distinct vec))

(defn document-title
  "Resolve optional module-owned title selection against a public snapshot."
  [spec snapshot]
  (or (when-let [title (:document-title spec)] (title snapshot))
      (get-in snapshot [:content :document :title])))

(defn critical-image-paths
  "Shared content paths for the heading and declared first-paint images."
  [spec]
  (let [heading (get-in spec [:shell :heading])]
    (vec (distinct (concat (when heading [(conj (vec heading) :bg :src)])
                           (:preload-images spec))))))

(defn critical-images
  "The page's shared heading and explicitly declared first-paint content images."
  [spec content]
  (->> (critical-image-paths spec)
       (keep #(get-in content %))
       (filter string?) distinct vec))

(defn critical-image-attrs
  "Project each first-paint image's rendered source, ready manifest and sizes.
   String-only CMS fields use their section-owned image-assets sidecar."
  [spec content]
  (->> (critical-image-paths spec)
       (keep (fn [path]
               (let [src (get-in content path)
                     parent (get-in content (pop path))
                     asset (or (when (map? parent) (:asset parent))
                               (loop [prefix (pop path)]
                                 (or (get-in content (into prefix [:image-assets src]))
                                     (when (seq prefix) (recur (pop prefix))))))]
                 (when (string? src)
                   (cond-> {:src (or (:src asset) src)}
                     asset (assoc :asset asset)
                     (and (map? parent) (:sizes parent)) (assoc :sizes (:sizes parent)))))))
       distinct vec))
