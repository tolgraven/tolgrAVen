(ns tolgraven.component.compat
  "Version-specific Reagent 2.0.1 Hiccup metadata boundary."
  (:require [reagent.core :as r]))

(defn argv
  "Return original Hiccup for the current class/function component. React keys
   are not component props, and Reagent's public class argv accessor does not
   expose function-component metadata. Keep the 2.0.1 representation here only;
   keyed persistence and mounted macro tests must pass on library upgrades."
  []
  (when-let [instance (r/current-component)]
    (if-let [arguments (.-argv ^clj instance)]
      ;; functional-render stores the argument subvec, whose backing Hiccup
      ;; retains the React key. Do not inspect React element props/internals.
      (if (instance? cljs.core/Subvec arguments)
        (.-v ^cljs.core/Subvec arguments)
        arguments)
      (r/argv instance))))
