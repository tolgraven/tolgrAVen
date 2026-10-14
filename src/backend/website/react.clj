(ns website.react
  "Call-site preserving re-frame macros for the shared React/Reagent shim."
  (:require [cljs.env :as env]
            [reagent.core :as reagent]
            [re-frame.core-instrumented :as instrumented]))

(defn- compiler-debug? []
  (when env/*compiler*
    (let [options (:options @env/*compiler*)
          defines (:closure-defines options)]
      (cond
        (contains? defines 'goog.DEBUG) (get defines 'goog.DEBUG)
        (contains? defines "goog.DEBUG") (get defines "goog.DEBUG")
        (= :advanced (:optimizations options)) false))))

(defn- expand-call [operation form compiler-env args]
  ;; Choose the regular API before CLJS analysis in production. With no compiler
  ;; context, retain upstream's own goog.DEBUG guard (e.g. JVM macro tooling).
  (if (false? (compiler-debug?))
    (cons (symbol "re-frame.core" (name operation)) args)
    (apply @(ns-resolve 're-frame.core-instrumented operation) form compiler-env args)))

(defn- registrar [operation]
  (fn [form compiler-env & args]
    (expand-call operation form compiler-env args)))

(defmacro dispatch [& args]
  (expand-call 'dispatch &form &env args))

(defmacro dispatch-sync [& args]
  (expand-call 'dispatch-sync &form &env args))

(defmacro subscribe [& args]
  (let [query (gensym "query")]
    `(let [~query (website.validation.bindings/query ~(first args))]
       (website.react/observe-subscription! ~query
         ~(expand-call 'subscribe &form &env (cons query (rest args)))))))

(defn- contract? [value]
  (and (map? value) (some #(contains? value %) [:args :result :coerce :result-coerce :on-error])))

(defn- event-registration [registrar form env id decls]
  (if-not (contract? (first decls))
    (let [id-sym (gensym "id")]
      `(let [~id-sym ~id]
         (website.validation.bindings/unregister! :event ~id-sym)
         ~(apply registrar form env id-sym decls)))
    (let [[options & body] decls
          _ (when-not (#{1 2} (count body))
              (throw (ex-info "Event contract expects optional interceptors and one handler" {:id id})))
          interceptors (when (= 2 (count body)) (first body))
          options-sym (gensym "contract") id-sym (gensym "id")]
      `(let [~id-sym ~id
             ~options-sym (website.validation.bindings/register! :event ~id-sym ~options)]
         ~(registrar form env id-sym
                     `[(website.validation.bindings/event-interceptor ~id-sym ~options-sym) ~interceptors]
                     (last body))))))

(defmacro reg-event-db [& args]
  (event-registration (registrar 'reg-event-db) &form &env (first args) (rest args)))

(defmacro reg-event-fx [& args]
  (event-registration (registrar 'reg-event-fx) &form &env (first args) (rest args)))

(defn- subscription-registration [registrar wrapper form env id decls]
  (if-not (contract? (first decls))
    (let [id-sym (gensym "id")]
      `(let [~id-sym ~id]
         (website.validation.bindings/unregister! :sub ~id-sym)
         ~(apply registrar form env id-sym decls)))
    (let [[options & body] decls
          options-sym (gensym "contract") id-sym (gensym "id")]
      `(let [~id-sym ~id
             ~options-sym (website.validation.bindings/register! :sub ~id-sym ~options)]
         ~(apply registrar form env id-sym
                 (concat (butlast body) [`(~wrapper ~id-sym ~options-sym ~(last body))]))))))

(defmacro reg-sub [& args]
  (subscription-registration (registrar 'reg-sub) 'website.validation.bindings/computation
                             &form &env (first args) (rest args)))

(defmacro reg-sub-raw [& args]
  (subscription-registration (registrar 'reg-sub-raw) 'website.validation.bindings/source
                             &form &env (first args) (rest args)))

(defmacro reg-fx [& args]
  (expand-call 'reg-fx &form &env args))

(defmacro reg-cofx [& args]
  (expand-call 'reg-cofx &form &env args))

(defmacro with-let [& args]
  (apply @#'reagent/with-let &form &env args))

(defmacro defc [& args]
  (apply @#'reagent/defc &form &env args))

(defmacro reaction [& args]
  (apply @#'reagent/reaction &form &env args))

(def ^:private framework-bindings
  {'dispatch 're-frame.core/dispatch
   'dispatch-sync 're-frame.core/dispatch-sync
   'subscribe 're-frame.core/subscribe
   '*app-db 're-frame.db/app-db
   '*subscription-cache 're-frame.subs/query->reaction})

(defmacro with-framework-bindings
  "Scope existing framework state/API replacements for SSR and test adapters.
   Evaluate each supplied value once and restore both facade and native owners."
  [bindings & body]
  (let [pairs (mapv (fn [[target value]]
                      {:target target, :value value, :local (gensym "value")})
                    (partition 2 bindings))
        aliases (get-in &env [:ns :requires])
        native-target (fn [target]
                        (when (and (symbol? target)
                                   (or (= "website.react" (namespace target))
                                       (= 'website.react (get aliases (symbol (or (namespace target) ""))))))
                          (get framework-bindings (symbol (name target)))))
        replacements (mapcat
                       (fn [{:keys [target local]}]
                         (let [native (native-target target)]
                           (cond-> [target local]
                             native (into [native local])
                             (and native (#{'*app-db '*subscription-cache} (symbol (name target))))
                             (into [(symbol "website.react.runtime" (name target)) local]))))
                       pairs)]
    `(let [~@(mapcat (juxt :local :value) pairs)]
       (with-redefs [~@replacements] ~@body))))

(defmacro dispatch-with [& args]
  (expand-call 'dispatch-with &form &env args))

(defmacro dispatch-sync-with [& args]
  (expand-call 'dispatch-sync-with &form &env args))

(defmacro dispatch-and-settle [& args]
  (expand-call 'dispatch-and-settle &form &env args))
