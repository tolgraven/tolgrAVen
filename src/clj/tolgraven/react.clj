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
  (apply @#'instrumented/subscribe &form &env args))

(defmacro reg-event-db [& args]
  (apply @#'instrumented/reg-event-db &form &env args))

(defmacro reg-event-fx [& args]
  (apply @#'instrumented/reg-event-fx &form &env args))

(defmacro reg-sub [& args]
  (apply @#'instrumented/reg-sub &form &env args))

(defmacro reg-sub-raw [& args]
  (apply @#'instrumented/reg-sub-raw &form &env args))

(defmacro reg-fx [& args]
  (apply @#'instrumented/reg-fx &form &env args))

(defmacro reg-cofx [& args]
  (apply @#'instrumented/reg-cofx &form &env args))
