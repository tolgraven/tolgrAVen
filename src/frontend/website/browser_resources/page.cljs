(ns website.browser-resources.page
  "Cancellable native page-readiness gates shared by optional resource adapters."
  (:require [website.component.restore :as restore]
            [website.listener :as listener]
            [website.render-context :as context]))

(defn after-page!
  ([load!] (after-page! load! {}))
  ([load! {:keys [layout? settle-ms] :or {layout? true, settle-ms 0}}]
    (let [*active? (atom true)
          *frame (atom nil)
          *idle (atom nil)
          *timer (atom nil)
          *ready-since (atom nil)
          *stop-listeners (atom nil)]
      (letfn [(idle! [& [deadline]]
                (when @*active?
                  (if (and @context/*interactive?
                           (not (:hydrate? @restore/*context))
                           (or (not layout?) (restore/layout-ready?))
                           (or (nil? deadline) (not (.-timeRemaining deadline))
                               (>= (.timeRemaining deadline) 8)))
                    (let [now (.now js/performance)
                          since (or @*ready-since now)]
                      (reset! *ready-since since)
                      (if (>= (- now since) settle-ms)
                        (do (reset! *active? false) (load!))
                        (reset! *timer (js/setTimeout painted! 100))))
                    (do (reset! *ready-since nil)
                        (reset! *timer (js/setTimeout painted! 100))))))
              (queue-idle! []
                (when @*active?
                  (if (.-requestIdleCallback js/window)
                    (reset! *idle (js/requestIdleCallback idle!))
                    (reset! *timer (js/setTimeout idle! 200)))))
              (painted! []
                (when @*active?
                  (reset! *frame
                          (js/requestAnimationFrame
                           (fn []
                             (when @*active?
                               (reset! *frame (js/requestAnimationFrame queue-idle!))))))))]
        (if (= "complete" (.-readyState js/document))
          (painted!)
          (reset! *stop-listeners
            (listener/listen! :browser-resources
              [{:target "window", :event "load", :handler painted!, :once? true}])))
        (fn []
          (reset! *active? false)
          (when-let [stop! @*stop-listeners] (stop!))
          (when @*frame (js/cancelAnimationFrame @*frame))
          (when @*idle (js/cancelIdleCallback @*idle))
          (when @*timer (js/clearTimeout @*timer)))))))
