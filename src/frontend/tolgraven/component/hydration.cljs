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
  (let [owner (js-obj)]
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
  (let [[*release] (rf/use-state #(atom nil))
        complete! (rf/use-callback
                    (fn []
                      (when-let [release! @*release]
                        (reset! *release nil)
                        (release!)))
                    #js [])]
    (rf/use-layout-effect
      (fn []
        (if defer?
          (do (reset! *release (hold!)) complete!)
          js/undefined))
      #js [])
    complete!))
