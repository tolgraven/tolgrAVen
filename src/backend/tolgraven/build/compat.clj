(ns tolgraven.build.compat
  (:require [clojure.string :as string]
            [shadow.build.data :as data]))

(defn rrb-source
  "core.rrb-vector through 0.2.1 omits Vector/->Vector from its core exclusions.
   Newer CLJS resolves its generated positional constructor to cljs.core.Vector.
   Patch only the namespace declaration; leave the library implementation intact."
  [source]
  (string/replace source
                  "(:refer-clojure :exclude [array-for push-tail pop-tail new-path do-assoc])"
                  "(:refer-clojure :exclude [Vector ->Vector array-for push-tail pop-tail new-path do-assoc])"))

(defn rrb-vector
  {:shadow.build/stage :compile-prepare}
  [state]
  (reduce-kv
   (fn [state id resource]
     (if (= 'clojure.core.rrb-vector.rrbt (:ns resource))
       (let [source (data/get-source-code state resource)
             patched (rrb-source source)]
         (if (= source patched) state
           (-> state
               (assoc-in [:sources id :source] patched)
               ;; Do not reuse analysis/JS generated from the unpatched jar.
               (update-in [:sources id :cache-key] conj ::exclude-core-vector))))
       state)) state (:sources state)))
