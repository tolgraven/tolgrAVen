(ns website.component.hydration
  "Keep updates to pending SSR boundaries independent of surrounding UI."
  (:require [website.react :as r]))

(defn use-boundary
  "Return a stable native Suspense element, deferring its updates during initial
   hydration. Dependencies must include every input used by render-element."
  [defer? render-element dependencies]
  (let [element (r/use-memo render-element dependencies)
        deferred (r/use-deferred-value (when defer? element))]
    ;; A synchronous Reagent update must not change an unhydrated boundary's
    ;; props. React retains this exact element while a background render waits
    ;; for its lazy child; unrelated shell controls keep normal urgent batching.
    ;; Memoization matters: React compares deferred values by native identity.
    (if defer? deferred element)))
