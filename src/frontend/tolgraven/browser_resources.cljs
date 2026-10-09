(ns tolgraven.browser-resources
  "Acquire optional scripts after hydration, load, pending page work and a painted idle frame."
  (:require [tolgraven.component.restore :as restore]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            ["react-dom" :as react-dom]))

(defonce *started? (atom false))

(defn after-page! [load!]
  (let [*active? (atom true)
        *frame (atom nil)
        *idle (atom nil)
        *timer (atom nil)]
    (letfn [(idle! []
              (when @*active?
                (if (restore/layout-ready?)
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
    (when-let [id (some-> (.querySelector js/document "meta[name=analytics-id]")
                         (.getAttribute "content"))]
      (when (re-matches #"G-[A-Z0-9]+" id)
        (react-dom/preinit (str "https://www.googletagmanager.com/gtag/js?id=" id)
                          #js {:as "script" :fetchPriority "low"})))
    ;; Native smooth scrolling needs no compatibility download. Older browsers
    ;; acquire the existing polyfill through React's resource API after paint.
    (when-not (some? (.-scrollBehavior (.-style (.-documentElement js/document))))
      (react-dom/preinit "https://unpkg.com/smoothscroll-polyfill@0.4.4/dist/smoothscroll.min.js"
                        #js {:as "script" :fetchPriority "low"}))))

(defn start! []
  (after-page! acquire!))

(defc <deferred> []
  (rf/use-effect (fn [] (or (start!) js/undefined)) #js [])
  [:<>])
