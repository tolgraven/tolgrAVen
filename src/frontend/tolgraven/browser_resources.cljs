(ns tolgraven.browser-resources
  "Acquire optional scripts after hydration, load, pending page work and a painted idle frame."
  (:require [tolgraven.component.restore :as restore]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            ["react-dom" :as react-dom]))

(defonce *started? (atom false))

(defn after-page! [load!]
  (let [*active? (atom true)
        *frame (atom nil)
        *idle (atom nil)
        *timer (atom nil)]
    (letfn [(idle! []
              (when @*active?
                (if (and @context/*interactive?
                         (not (:hydrate? @restore/*context))
                         (restore/layout-ready?))
                  (load!)
                  (reset! *timer (js/setTimeout painted! 100)))))
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
        (.addEventListener js/window "load" painted! #js {:once true}))
      (fn []
        (reset! *active? false)
        (.removeEventListener js/window "load" painted!)
        (when @*frame (js/cancelAnimationFrame @*frame))
        (when @*idle (js/cancelIdleCallback @*idle))
        (when @*timer (js/clearTimeout @*timer))))))

(defn- acquire! []
  (when (compare-and-set! *started? false true)
    (when-not ^boolean goog.DEBUG
      ;; Own the entire GA bootstrap here: SSR must not emit its queue, ID or
      ;; scripts. Queue configuration before React starts the remote download.
      (aset js/window "dataLayer" (or (aget js/window "dataLayer") #js []))
      (aset js/window "gtag"
            ;; GA consumes the native arguments object, not a CLJS sequence.
            (fn [] (.push (aget js/window "dataLayer") (js* "arguments"))))
      ((aget js/window "gtag") "js" (js/Date.))
      ((aget js/window "gtag") "config" "G-Y8H6RLZX3V")
      (react-dom/preinit "https://www.googletagmanager.com/gtag/js?id=G-Y8H6RLZX3V"
                        #js {:as "script" :fetchPriority "low"}))))

(defn start! []
  (after-page! acquire!))

(defc <deferred> []
  (rf/use-effect (fn [] (or (start!) js/undefined)) #js [])
  [:<>])
