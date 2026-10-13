(ns bootstrap.images
  "Head-time history policy, deliberately independent of the application runtime.")

(defn- direct! []
  (.setAttribute (.-documentElement js/document) "data-image-direct" "true"))

(defn init! []
  (let [navigation (aget (.getEntriesByType js/performance "navigation") 0)]
    (when (or (identical? "back_forward" (some-> navigation .-type))
              (identical? 2 (some-> js/performance .-navigation .-type)))
      (direct!)))
  ;; Only document roots change. Lazy previews remain undiscovered on native Back;
  ;; component rendering later removes them while preserving the final image.
  (.addEventListener js/window "pagehide" direct!)
  (.addEventListener js/window "pageshow"
                     (fn [^js event] (when (.-persisted event) (direct!)))))
