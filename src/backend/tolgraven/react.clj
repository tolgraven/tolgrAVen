(ns tolgraven.react
  "Call-site preserving re-frame macros for the shared React/Reagent shim."
  (:require [re-frame.core-instrumented :as instrumented]))

;; Forward the original form and compiler environment so tooling records the
;; caller, not this shim. Upstream macros branch on goog.DEBUG through
;; re-frame.interop/debug-enabled?; Closure removes instrumentation in release.

(defmacro dispatch [& args]
  (apply @#'instrumented/dispatch &form &env args))

(defmacro dispatch-sync [& args]
  (apply @#'instrumented/dispatch-sync &form &env args))

(defmacro subscribe [& args]
  (let [query (gensym "query")]
    `(let [~query (tolgraven.validation.bindings/query ~(first args))]
       (tolgraven.react/observe-subscription! ~query
         ~(apply @#'instrumented/subscribe &form &env (cons query (rest args)))))))

(defn- contract? [value]
  (and (map? value) (some #(contains? value %) [:args :result :coerce :result-coerce :on-error])))

(defn- event-registration [registrar form env id decls]
  (if-not (contract? (first decls))
    (let [id-sym (gensym "id")]
      `(let [~id-sym ~id]
         (tolgraven.validation.bindings/unregister! :event ~id-sym)
         ~(apply registrar form env id-sym decls)))
    (let [[options & body] decls
          _ (when-not (#{1 2} (count body))
              (throw (ex-info "Event contract expects optional interceptors and one handler" {:id id})))
          interceptors (when (= 2 (count body)) (first body))
          options-sym (gensym "contract") id-sym (gensym "id")]
      `(let [~id-sym ~id
             ~options-sym (tolgraven.validation.bindings/register! :event ~id-sym ~options)]
         ~(registrar form env id-sym
                     `[(tolgraven.validation.bindings/event-interceptor ~id-sym ~options-sym) ~interceptors]
                     (last body))))))

(defmacro reg-event-db [& args]
  (event-registration @#'instrumented/reg-event-db &form &env (first args) (rest args)))

(defmacro reg-event-fx [& args]
  (event-registration @#'instrumented/reg-event-fx &form &env (first args) (rest args)))

(defn- subscription-registration [registrar wrapper form env id decls]
  (if-not (contract? (first decls))
    (let [id-sym (gensym "id")]
      `(let [~id-sym ~id]
         (tolgraven.validation.bindings/unregister! :sub ~id-sym)
         ~(apply registrar form env id-sym decls)))
    (let [[options & body] decls
          options-sym (gensym "contract") id-sym (gensym "id")]
      `(let [~id-sym ~id
             ~options-sym (tolgraven.validation.bindings/register! :sub ~id-sym ~options)]
         ~(apply registrar form env id-sym
                 (concat (butlast body) [`(~wrapper ~id-sym ~options-sym ~(last body))]))))))

(defmacro reg-sub [& args]
  (subscription-registration @#'instrumented/reg-sub 'tolgraven.validation.bindings/computation
                             &form &env (first args) (rest args)))

(defmacro reg-sub-raw [& args]
  (subscription-registration @#'instrumented/reg-sub-raw 'tolgraven.validation.bindings/source
                             &form &env (first args) (rest args)))

(defmacro reg-fx [& args]
  (apply @#'instrumented/reg-fx &form &env args))

(defmacro reg-cofx [& args]
  (apply @#'instrumented/reg-cofx &form &env args))
