(ns tolgraven.component.hydration
  "Reagent batching during selective hydration of completed SSR boundaries."
  (:require [reagent.impl.batching :as batching]
            [tolgraven.react :as rf]))

(defonce ^:private *owners (atom #{}))
(defonce ^:private *previous-flush (atom nil))

(defn release-all!
  "Navigation abandons the old document's pending boundaries."
  []
  (reset! *owners #{})
  (when (and @*previous-flush
             (identical? batching/react-flush rf/start-transition))
    (set! batching/react-flush @*previous-flush))
  (reset! *previous-flush nil))

(defn- hold! []
  (let [owner (random-uuid)]
    (when (empty? @*owners)
      ;; Reagent 2 exposes this batching adapter and hydrate-root sets it to
      ;; flushSync. Urgent ancestor updates would discard unhydrated Suspense
      ;; content. React transitions let those boundaries finish hydrating first.
      (reset! *previous-flush batching/react-flush)
      (set! batching/react-flush rf/start-transition))
    (swap! *owners conj owner)
    (fn []
      (when (contains? @*owners owner)
        (when (empty? (swap! *owners disj owner))
          (release-all!))))))

(defn use-deferred!
  "Own one initial boundary until its inner layout commits or it unmounts.
   Returns the inner component's stable completion callback."
  [defer?]
  (let [[*owner] (rf/use-state #(atom {:complete? false, :release! nil}))
        complete! (rf/use-callback
                    (fn []
                      ;; Child layout effects precede their parent's. A cached
                      ;; formatter can complete before hold! has run at all.
                      (let [release! (:release! @*owner)]
                        (swap! *owner assoc :complete? true :release! nil)
                        (when release! (release!))))
                    [])]
    (rf/use-layout-effect
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
