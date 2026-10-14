(ns website.component.hydration
  "Reagent batching during selective hydration of completed SSR boundaries."
  (:require [website.react :as r]))

(defonce ^:private *owners (atom #{}))
(defonce ^:private *previous-flush (atom nil))

(defn release-all!
  "Navigation abandons the old document's pending boundaries."
  []
  (reset! *owners #{})
  (when (and @*previous-flush
             (identical? (r/batching-flush) r/start-transition))
    (r/set-batching-flush! @*previous-flush))
  (reset! *previous-flush nil))

(defn- hold! []
  (let [owner (random-uuid)]
    (when (empty? @*owners)
      ;; Reagent 2 exposes this batching adapter and hydrate-root sets it to
      ;; flushSync. Urgent ancestor updates would discard unhydrated Suspense
      ;; content. React transitions let those boundaries finish hydrating first.
      (reset! *previous-flush (r/batching-flush))
      (r/set-batching-flush! r/start-transition))
    (swap! *owners conj owner)
    (fn []
      (when (contains? @*owners owner)
        (when (empty? (swap! *owners disj owner))
          (release-all!))))))

(defn use-deferred!
  "Own one initial boundary until its inner layout commits or it unmounts.
   Returns the inner component's stable completion callback."
  [defer?]
  (let [[*owner] (r/use-state #(atom {:complete? false, :release! nil}))
        complete! (r/use-callback
                    (fn []
                      ;; Child layout effects precede their parent's. A cached
                      ;; formatter can complete before hold! has run at all.
                      (let [release! (:release! @*owner)]
                        (swap! *owner assoc :complete? true :release! nil)
                        (when release! (release!))))
                    [])]
    (r/use-layout-effect
      (fn []
        (when (and defer? (not (:complete? @*owner)))
          (let [release! (hold!)]
            (swap! *owner assoc :release! release!)
            (fn []
              (when (identical? release! (:release! @*owner))
                (swap! *owner assoc :release! nil))
              (release!)))))
      [defer?])
    complete!))
