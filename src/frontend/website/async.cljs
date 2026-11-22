(ns website.async
  "Promise interop for callbacks, cancellation owners and concurrent acquisition.
  Sequential tasks can use ClojureScript's ^:async functions and await.")

(defn create [start!] (js/Promise. start!))
(defn resolved [value] (js/Promise.resolve value))
(defn rejected [error] (js/Promise.reject error))
(defn then
  ([pending success] (.then pending success))
  ([pending success failure] (.then pending success failure)))
(defn catch-error [pending recover] (.catch pending recover))
(defn finally-do [pending cleanup!] (.finally pending cleanup!))

(defn all [pending]
  (then (js/Promise.all (into-array pending)) vec))

(defn race [pending] (js/Promise.race (into-array pending)))

(defn- outcome [entry]
  (if (= "fulfilled" (.-status entry))
    {:status :fulfilled :value (.-value entry)}
    {:status :rejected :reason (.-reason entry)}))

(defn all-settled [pending]
  (then (js/Promise.allSettled (into-array pending)) #(mapv outcome %)))

(defn delay! [ms] (create (fn [resolve! _] (js/setTimeout #(resolve! nil) ms))))

(defn deferred
  "Return a pending promise and its completion functions for an external event."
  []
  (let [callbacks (volatile! nil)
        pending (create (fn [resolve! reject!] (vreset! callbacks {:resolve! resolve! :reject! reject!})))]
    (assoc @callbacks :promise pending)))
