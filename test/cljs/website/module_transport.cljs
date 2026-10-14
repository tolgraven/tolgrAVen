(ns website.module-transport
  "Controlled real loader/CSS readiness for mounted ordering regressions."
  (:require [website.loader :as loader]
            [website.loader.styles :as styles]
            [shadow.lazy :as lazy]))

(defn install! [specs]
  (let [before {:modules loader/modules
                :css styles/acquire!
                :ready? styles/ready?
                :loads @loader/*loads
                :code-loads @loader/*code-loads
                :installed @loader/*installed}
        *started (atom #{})
        *ready (atom #{})
        *releases (atom {})
        gates (into {} (for [id (keys specs)]
                         [id (js/Promise. (fn [resolve _] (swap! *releases assoc id resolve)))]))
        loadables (into {} (for [[id spec] specs]
                             [id (reify lazy/ILoadable
                                   (ready? [_] true)
                                   IDeref (-deref [_] spec))]))]
    ;; Real owner IDs may already have completed in earlier browser tests.
    ;; Preserve unrelated owners, but force controlled acquisitions through gates.
    (swap! loader/*loads #(apply dissoc % (keys specs)))
    (swap! loader/*code-loads #(apply dissoc % (keys specs)))
    (swap! loader/*installed #(apply disj % (keys specs)))
    (set! loader/modules (merge loader/modules loadables))
    (set! styles/acquire!
      (fn [id]
        (if-let [gate (get gates id)]
          (do (swap! *started conj id) gate)
          ((:css before) id))))
    (set! styles/ready?
      (fn [id]
        (if (contains? specs id)
          (contains? @*ready id)
          ((:ready? before) id))))
    {:started *started
     :release! (fn [id] (swap! *ready conj id) ((get @*releases id) nil))
     :restore! (fn []
                 (set! loader/modules (:modules before))
                 (set! styles/acquire! (:css before))
                 (set! styles/ready? (:ready? before))
                 (reset! loader/*loads (:loads before))
                 (reset! loader/*code-loads (:code-loads before))
                 (reset! loader/*installed (:installed before)))}))
