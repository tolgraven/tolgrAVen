(ns bootstrap.destination
  "Position native completed SSR before the document entrance, without app imports.")

(defn- layout-top [element]
  ;; Paused entrance transforms change client rectangles; layout offsets retain
  ;; the final anchor position while the document's animation clock is held.
  (loop [node element, top 0]
    (if node (recur (.-offsetParent node) (+ top (.-offsetTop node))) top)))

(defn await! [root complete!]
  (if-let [id (.getAttribute root "data-document-target")]
    (let [path (.-pathname js/location)
          owner #js {:active true :frame nil :previous nil :deadline nil :observer nil}]
      (letfn [(finish! []
                (when (.-active owner)
                  (set! (.-active owner) false)
                  (js/clearTimeout (.-deadline owner))
                  (js/cancelAnimationFrame (.-frame owner))
                  (.disconnect (.-observer owner))
                  (.removeEventListener js/window "pagehide" finish!)
                  (.removeEventListener js/document "load" schedule! true)
                  (complete!)))
              (measure! []
                (set! (.-frame owner) nil)
                (when (.-active owner)
                  (if-not (identical? path (.-pathname js/location))
                    (finish!)
                    (when-let [element (.getElementById js/document id)]
                      (when (and (> (.-offsetHeight element) 0)
                                 (not (identical? "none" (.-display (js/getComputedStyle root))))
                                 (not (identical? "loading" (some-> js/document .-fonts .-status))))
                        (let [margin (js/parseFloat (.-scrollMarginTop (js/getComputedStyle element)))
                              top (- (layout-top element) (if (js/isNaN margin) 0 margin))
                              geometry (.join #js [top (.-scrollHeight (.-documentElement js/document)) js/innerWidth] ":")]
                          (if (identical? geometry (.-previous owner))
                            (do
                              (.scrollTo js/window #js {:top top :left 0 :behavior "instant"})
                              (.setAttribute root "data-document-positioned" id)
                              (finish!))
                            (do (set! (.-previous owner) geometry)
                                (schedule!)))))))))
              (schedule! [& _]
                (when (and (.-active owner) (nil? (.-frame owner)))
                  (set! (.-frame owner) (js/requestAnimationFrame measure!))))]
        (set! (.-observer owner) (js/MutationObserver. schedule!))
        (.observe (.-observer owner) root #js {:subtree true :childList true :attributes true})
        ;; The trailing completion marker is outside React's mount container.
        ;; Its arrival can make a streamed target measurable before hydration.
        (.observe (.-observer owner) (.-body js/document) #js {:childList true})
        (.addEventListener js/window "pagehide" finish!)
        (.addEventListener js/document "load" schedule! true)
        (when-let [fonts (.-fonts js/document)] (.then (.-ready fonts) schedule!))
        ;; Missing/failed targets never strand the page. The ordinary mounted
        ;; component owns any later smooth scroll after this bounded fallback.
        (set! (.-deadline owner) (js/setTimeout finish! 5000))
        (schedule!)))
    (complete!)))
