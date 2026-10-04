(ns tolgraven.macros
  #?(:clj (:refer-clojure :exclude [for tap>]))
  (:require #?(:clj [cljs.env :as cljs-env])
            [clojure.string :as string]
            [malli.core :as m]
            [malli.error :as me]
            #?@(:cljs [[reagent.core :as r]
                       [tolgraven.component]
                       [tolgraven.react :as rf]
                       [shadow.lazy]
                       [tolgraven.components.error :as error]
                       [tolgraven.util :as util]]))
  #?(:cljs (:require-macros [tolgraven.macros])))

(defmacro hafn "Use in event-handlers instead of (fn [e/_]), returns nil so react doesnt get a false and ignore us"
  ([& body]
    `(fn [~'event] ~@body nil)))  ;; force return nil

(defmacro ors "(or), but treats empty string as nil. not working"
  ([] nil)
  ([x]
   (when-not (clojure.string/blank? x)
     x))
  ([x & next]
   `(let [or# ~x]
          (if or# or# (ors ~@next)))))

(defmacro for
  "Like `clojure.core/for`, but non-lazy, and injects `:key` metadata into each
  item."
  [[id xs & ls] <c>]
  `(doall (clojure.core/for [[i# ~id] (map-indexed vector ~xs) ~@ls]
            (with-meta ~<c> {:key i#}))))

(defmacro tap>
  "Tap a value and return it for use in threading expressions."
  [x]
  `(let [val# ~x]
     (clojure.core/tap> val#)
     val#))

(defmacro browser-only
  "Exclude browser module references from the SSR compilation graph."
  [form]
  (when-not (get-in @cljs-env/*compiler* [:options :external-config :tolgraven/ssr]) form))

(defmacro make-modules
  "Use keywords to generate Shadow lazy loadables.
   ks must be a literal seq of keywords at the call site."
  [base ks]
  (let [base-str (str base)]
    `(hash-map
       ~@(mapcat
           (fn [k]
             (let [qsym# (symbol (str base-str "." (name k) ".module/spec"))]
               [k `(shadow.lazy/loadable ~qsym#)]))
           ks))))


;; also should include tooltip popup functionality
;; other possible route is middleware style where each piece of functionality
;; is merged onto the component? but could that actually be done without wrapping?
;;
;; random thought:
;; if doing placeholders and (content) loading support, how determine it?
;; might want to hook something on when all subcomponents are ready somehow.
;; if use a (reader?) macro at usage time, could gather such stuff.
;; but would be best _after_ reagent does its walk?
;; skip all that in beginning anyways.
;;
;; basic feature flag/disable component entirely support could go in here as well
;; prob do some lookups by sub through passing a namespaced id key in spec
;; including whether is enabled
(defmacro defc
  "Define a lean Reagent 2 function component with optional composed features.

   Plain definitions delegate directly to reagent.core/defc. An optional spec
   declaration map after the optional docstring declares :features and :depends. Features
   include :error-boundary, :props, :lifecycle, :links, and registered extensions.
   Merge-only features run inline with hooks; only error boundaries need classes.
   :appear and :seen merge onto a native root; :presence retains keyed :exit
   children until their exit animations finish.

   :depends is a vector of data-source descriptors, or a function of component
   arguments returning that vector. Dependencies start before mount and can be
   explicitly prefetched with component/preload!. :data enables dependencies
   supplied through the first argument at runtime. Spec arguments are inferred
   from spec/opts/options bindings or destructured feature keys; domain-data
   arguments need no opt-out annotation.

   Optional :let [bindings] initializes per-instance state. Existing form-2
   bodies, destructured/variadic arguments, docstrings and metadata are supported.
   Use components in Hiccup, not as ordinary functions (Reagent 2 convention).

   (defc <counter> {:features [:props :error-boundary]} [{:keys [label] :as spec}]
     :let [*count (reagent.core/atom 0)]
     [:button {:on-click #(swap! *count inc)} label @*count])"
  [name & decls]
  (let [docstring (when (string? (first decls)) (first decls))
        decls (if docstring (next decls) decls)
        attrs (when (map? (first decls)) (first decls))
        decls (if attrs (next decls) decls)
        [args & body] decls
        [bindings body] (if (= :let (first body))
                          [(second body) (nnext body)]
                          [[] body])
        metadata (merge (meta name) attrs
                        (when docstring {:doc docstring})
                        {:arglists (list 'quote (list args))})
        ns-name (or (some-> &env :ns :name) (ns-name *ns*))]
    (when-not (and (symbol? name) (vector? args) (vector? bindings)
                   (even? (count bindings)) (seq body))
      (throw (ex-info "defc requires an argument vector, optional :let bindings, and a body"
                      {:component name})))
    (let [scoped-helpers? (some #(and (seq? %) (symbol? (first %))
                                     (#{"<sub" ">reset" ">update"} (clojure.core/name (first %))))
                               (tree-seq coll? seq (concat bindings body)))
          options (select-keys (merge (meta name) attrs) [:spec :features :depends :loading :state :module])
          options (if (and scoped-helpers? (nil? (:state options))) (assoc options :state {}) options)
          helper-bindings (when scoped-helpers?
                            ['<sub 'tolgraven.component/<sub
                             '>reset 'tolgraven.component/>reset
                             '>update 'tolgraven.component/>update])
          spec-arg (first args)
          spec? (or (and (symbol? spec-arg) (#{"spec" "opts" "options"} (clojure.core/name spec-arg)))
                    (and (map? spec-arg)
                         (or (#{'spec 'opts 'options} (:as spec-arg))
                             (some #{'props 'classes 'depends 'appear 'seen 'links}
                                   (:keys spec-arg))
                             (some #{:props :classes :depends :appear :seen :links} (vals spec-arg)))))
          options (assoc options :spec (if (contains? options :spec) (:spec options) (boolean spec?)))
          plain? (and (empty? (:features options)) (nil? (:depends options)) (nil? (:state options)))
          descriptor (gensym "definition")]
      `(do
         (declare ~name)
         ;; Keep Reagent-generated render vars lexical: cached namespaces can
         ;; otherwise reuse top-level compiler gensyms after incremental builds.
         ((fn []
         (let [~descriptor (tolgraven.component/definition
                         ~(str ns-name) ~(str name) ~options
                         (fn ~args (let [~@helper-bindings ~@bindings] (fn ~args ~@body))))]
         ~(if plain?
            `(reagent.core/defc ~(with-meta name metadata) ~args
               ~@(if (seq bindings) [`(reagent.core/with-let ~bindings ~@body)] body))
            `(reagent.core/defc ~(with-meta name metadata) [& argv#]
               (tolgraven.component/render-component ~descriptor argv#)))
         (tolgraven.component/register-component! ~name ~descriptor))))))))

(defmacro defcomp-
  "Define a reagent component with a docstring and metadata, standardized arg
   parsing, but no extra functionality like error boundaries and such.
  Usage:
  (defcomp- <small-component>
    \"This is a light component\"
    [spec-or-form]
    (into [:div (:props spec)] form))
   Meaning (TODO) a single arg gets destructured as spec containing form, or
   component not taking a form."
  [name & decls]
  (let [docstring     (when (string? (first decls)) (first decls))
        decls         (if docstring (next decls) decls)
        meta-map      (when (map? (first decls)) (first decls))
        decls         (if meta-map (next decls) decls)
        [args & body] decls
        [lets body]   (if (and (seq body)
                               (= :let (first body))
                               (vector? (second body)))
                        [(second body) (nnext body)]
                        [nil body])
        spec-sym      (when (vector? args) (first args))]
    `(defn ~name
       ~@(when docstring [docstring])
       ~@(when meta-map [meta-map])
       ~args
       (let [spec#   ~spec-sym
             ~@(when lets [lets])]
         (reagent.core/create-class
          {:display-name ~(str name)
           :component-did-mount
           (fn [this#]
             (and (fn? (:init spec#))
                  ((:init spec#) this#)))
          :component-will-unmount
          (fn [this#]
            (and (fn? (:exit spec#))
                 ((:exit spec#) this#)))
           :reagent-render
           (fn ~(symbol (str name "-inner"))
             ~args
             (letfn [(merge-props-into-root# [el#]
                        ;; Only merge when: vector hiccup and not a fragment
                        (if (and (vector? el#)
                                 (not= :<> (first el#))
                                 ~@(when spec-sym [`(map? ~spec-sym)])) ; if no spec arg, skip
                          (let [[tag# maybe-attrs# & children#] el#
                                has-attrs?# (map? maybe-attrs#)
                                base-attrs# (if has-attrs?# maybe-attrs# {})
                                merged-attrs# (if ~spec-sym
                                                (merge base-attrs# (:props ~spec-sym))
                                                base-attrs#)
                                head# (if has-attrs?#
                                        [tag# merged-attrs#]
                                        [tag# merged-attrs#])]
                            (into head# (if has-attrs?# children# (cons maybe-attrs# children#))))
                          el#))]
               (merge-props-into-root# (do ~@body))))})))))
; (s/defn <boundary> :- r/Component
;   "Util for inserting an error boundaries in the React tree. When a child throws
;   an error, just renders that error instead of crashing."
;   [& args]
;   (let [*error   (atom nil)
;         spec     (when (map? (first args)) (first args))
;         on-catch (fn [this error i]
;                    (when-not standalone?
;                      (let [stack (.-componentStack ^js i)]
;                        (reset! *error [error stack])
;                        (when (conf/get-error-reports-enabled)
;                          (report-error! error stack)))
;                      (.forceUpdate ^js this)))]
;     (letfn [(<boundary-inner> [this]
;               (r/as-element
;                (if-let [[error stack] @*error]
;                  [<error-container> (assoc spec :error error :stack stack)]
;                  (into [:<>] (r/children this)))))]
;       (r/create-class
;        {:component-did-catch          on-catch
;         :component-did-update         #(reset! *error nil)
;         :display-name                 "error-boundary"
;         :get-derived-state-from-error #(reset! *error [%])
;         :render                       <boundary-inner>}))))
