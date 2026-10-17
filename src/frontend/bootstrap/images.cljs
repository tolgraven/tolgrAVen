(ns bootstrap.images
  "Head-time history policy, deliberately independent of the application runtime.")

(defn init! []
  (let [root (.-documentElement js/document)
        intents #js ["pointerdown" "keydown" "wheel" "touchstart" "click"]
        owner #js {:listening false}]
    (letfn [(interactive! [_]
              (.removeAttribute root "data-return-settling")
              (when (.-listening owner)
                (set! (.-listening owner) false)
                (.forEach intents #(.removeEventListener js/window % interactive! true))))
            (returned! []
              ;; Root policy precedes CSS, image discovery and hydration. Keep
              ;; automatic document clocks settled, then allow ordinary control
              ;; transitions as soon as the user interacts with this document.
              (.setAttribute root "data-image-direct" "true")
              (.setAttribute root "data-document-return" "true")
              (.setAttribute root "data-return-settling" "true")
              (when-not (.-listening owner)
                (set! (.-listening owner) true)
                (.forEach intents #(.addEventListener js/window % interactive!
                                                       #js {:capture true, :passive true}))))]
      (let [navigation (aget (.getEntriesByType js/performance "navigation") 0)]
        (when (or (identical? "back_forward" (some-> navigation .-type))
                  (identical? 2 (some-> js/performance .-navigation .-type)))
          (returned!)))
      (.addEventListener js/window "pagehide" returned!)
      (.addEventListener js/window "pageshow"
                         (fn [^js event] (when (.-persisted event) (returned!)))))))
