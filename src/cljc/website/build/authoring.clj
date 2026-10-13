(ns website.build.authoring
  "Extract selected public component identities and help without evaluating source."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader :as reader]
            [website.build.modules :as modules]
            [website.build.site :as site]))

(defn source-resource [namespace]
  (let [base (-> (str namespace) (string/replace "-" "_") (string/replace "." "/"))]
    (some #(when (io/resource %) %) (map #(str base %) [".cljs" ".cljc"]))))

(defn read-forms [source features]
  (let [stream (java.io.PushbackReader. (java.io.StringReader. source))]
    (binding [reader/*read-eval* false
              reader/*data-readers* (assoc reader/*data-readers* 'js identity 'queue identity)]
      (vec (take-while #(not= ::eof %)
                      (repeatedly #(reader/read {:eof ::eof, :read-cond :allow, :features features} stream)))))))

(defn required-namespaces [forms]
  (for [clause (drop 2 (first forms))
        :when (and (seq? clause) (#{:require :require-macros} (first clause)))
        entry (rest clause)
        :when (and (vector? entry) (symbol? (first entry)))]
    (first entry)))

(defn argument-shape [args]
  (if-not (vector? args) {:mode :positional, :parameters []}
    (let [clean-args (:args ((requiring-resolve 'website.macros/component-arguments) args))
          first-arg (first clean-args)
          spec? (or (map? first-arg)
                    (and (symbol? first-arg) (#{"spec" "opts" "options"} (name first-arg))))]
      {:mode (if (and spec? (= 1 (count clean-args))) :map :positional)
       :parameters (mapv pr-str clean-args)})))

(defn declarations [source enabled?]
  (let [forms (read-forms source (cond-> #{:cljs :browser} (enabled? :blog :comments) (conj :feature/blog-comments)))
        namespace (str (second (first forms)))]
    (letfn [(collect [form]
              (when (seq? form)
                (cond
                  (#{'quote 'comment} (first form)) nil

                  (and (symbol? (first form)) (= "when-enabled" (name (first form))))
                  (let [[_ owner feature & children] form]
                    (when (if feature (enabled? owner feature) (enabled? owner)) (mapcat collect children)))

                  (and (symbol? (first form))
                       (or (#{"defc" "defpage"} (name (first form)))
                           (and (#{"defn" "defcomp-"} (name (first form)))
                                (re-matches #"<.+>" (str (second form))))))
                  (let [[_ symbol & body] form
                        doc (when (string? (first body)) (first body))
                        body' (if doc (next body) body)
                        options (when (map? (first body')) (first body'))
                        body'' (if options (next body') body')]
                    (when-not (:private (meta symbol))
                      [(merge {:name (str namespace "/" symbol)
                               :symbol (str symbol)
                               :kind (keyword (name (first form)))
                               :namespace namespace
                               :doc (or doc "Documentation has not yet been added to this component.")
                               :schema (:spec-schema options)
                               :args-schema (:args-schema options)
                               :defaults (get-in options [:authoring :defaults])
                               :category (get-in options [:authoring :category])}
                              (cond-> (argument-shape (first body''))
                                (:spec-schema options) (assoc :mode :map)))]))

                  :else (mapcat collect (filter seq? form)))))]
      (vec (mapcat collect forms)))))

(defn selected-features []
  (into #{:cljs :browser}
        (concat (map #(keyword "module" (name (:id %))) (modules/discover))
                (when (site/enabled? :blog :comments) [:feature/blog-comments]))))

(defn entries [env]
  (site/track! env)
  (let [features (selected-features)
        owners (conj (modules/discover) (modules/declaration (io/file modules/root "main/module.cljs")))
        read! (memoize (fn [namespace]
                (when-let [resource (source-resource namespace)]
                  (when (:ns env) ((requiring-resolve 'shadow.resource/slurp-resource) env resource))
                  (read-forms (slurp (io/resource resource)) features))))
        closure (fn [entry]
                  (loop [todo [entry], seen #{}]
                    (if-let [namespace (first todo)]
                      (if (seen namespace) (recur (subvec todo 1) seen)
                        (let [forms (read! namespace)
                              dependencies (filter #(string/starts-with? (str %) "website.")
                                                   (required-namespaces forms))]
                          (recur (into (subvec todo 1) dependencies) (conj seen namespace))))
                      seen)))
        namespace-owners (reduce (fn [result {:keys [id entry]}]
                                  (reduce #(if (contains? %1 %2) %1 (assoc %1 %2 id)) result (closure entry)))
                                {} (sort-by #(if (= :main (:id %)) "" (name (:id %))) owners))]
    (->> namespace-owners
         (mapcat (fn [[namespace owner]]
                   (when (or (string/starts-with? (str namespace) "website.components.")
                             (string/starts-with? (str namespace) "website.modules."))
                     (let [resource (source-resource namespace)]
                       (map #(assoc % :owner owner)
                            (declarations (slurp (io/resource resource)) site/enabled?))))))
         (sort-by :name)
         vec)))

(defmacro catalog [] `(quote ~(entries &env)))
(defmacro owner-catalog []
  `(quote ~(into {} (map (juxt :name :owner)) (entries &env))))

(defmacro legacy-catalog []
  `(hash-map
     ~@(mapcat
         (fn [{:keys [name namespace symbol]}]
           [name `(let [loadable# (shadow.lazy/loadable ~(clojure.core/symbol namespace symbol))]
                    {:resolve (fn [] (when (shadow.lazy/ready? loadable#) @loadable#))
                     :definition {:ns ~namespace
                                  :name ~symbol
                                  :options {:public? true, :spec false}}})])
         (filter #(#{:defn :defcomp-} (:kind %)) (entries &env)))))

(defmacro register-render-functions!
  "Register real public legacy render functions once, without wrapping their call contracts."
  []
  (let [namespace (str (get-in &env [:ns :name]))
        resource (source-resource namespace)
        entries (when resource (declarations (slurp (io/resource resource)) site/enabled?))]
    (when (and resource (:ns &env))
      ((requiring-resolve 'shadow.resource/slurp-resource) &env resource))
    `(do
       ~@(for [{:keys [name symbol parameters args-schema kind]} entries
               :when (#{:defn :defcomp-} kind)
               :let [component (clojure.core/symbol symbol)]]
           `(website.component.registry/register-component!
              ~component
              (website.component.registry/definition
                ~namespace ~symbol
                ~(cond-> {:public? true, :spec false, :arglists (list 'quote parameters)}
                   args-schema (assoc :args-schema args-schema))
                nil))))))
