(ns bootstrap.skeleton
  "Remove only the unhydrated streamed shell, which lives outside React's #app.")

(defn watch! [^js root]
  (when-let [shell (.querySelector js/document "#ssr-shell")]
    (let [^js owner #js {:started false :finished false}]
      (letfn [(finish! []
                (when-not (.-finished owner)
                  (set! (.-finished owner) true)
                  (js/clearTimeout (.-deadline owner))
                  (.disconnect (.-observer owner))
                  (.removeEventListener shell "animationend" ended!)
                  (.removeEventListener shell "animationcancel" ended!)
                  (.remove shell)))
              (ended! [event]
                (when (and (identical? shell (.-target event))
                           (identical? "skeleton-dissolve" (.-animationName event)))
                  (finish!)))
              (ready! []
                (when (and (not (.-started owner))
                           (.querySelector js/document "#ssr-complete")
                           (not (.hasAttribute root "data-document-pending")))
                  (set! (.-started owner) true)
                  (let [style (js/getComputedStyle shell)
                        duration (* 1000 (js/parseFloat (.-animationDuration style)))]
                    (if (and (identical? "skeleton-dissolve" (.-animationName style))
                             (> duration 0))
                      ;; Native animation completion is primary. Bound cleanup
                      ;; if a browser suppresses the event in a hidden document.
                      (set! (.-deadline owner) (js/setTimeout finish! (+ duration 50)))
                      (finish!)))))]
        (set! (.-observer owner) (js/MutationObserver. ready!))
        (.addEventListener shell "animationend" ended!)
        (.addEventListener shell "animationcancel" ended!)
        (.observe (.-observer owner) root #js {:attributes true
                                             :attributeFilter #js ["data-document-pending"]})
        ;; The parser can still be awaiting the trailing stream marker when a
        ;; decoded hero opens the gate. Watch only direct document children.
        (when (.-body js/document)
          (.observe (.-observer owner) (.-body js/document) #js {:childList true}))
        (ready!)))))
