(ns website.components.timer
  (:require
    [website.component.registry]
    [website.macros :refer-macros [defc]]
    [website.react :as r]))

(defc <timeout>
  "Call f after mount, cancelling on unmount. Renders no DOM and is safe in Node."
  [f :- fn? milliseconds :- [:and number? [:>= 0]]]
  :let [*callback (atom f)]
  ;; Keep the mount deadline, while callbacks come from the latest committed
  ;; arguments. Returning f here would accidentally declare it as cleanup.
  (r/use-layout-effect (fn [] (reset! *callback f) nil) [f])
  (r/use-effect
   (fn []
     (let [timer (js/setTimeout #(@*callback) milliseconds)]
       #(js/clearTimeout timer))) [])
  nil)

(defn use-delayed-visible?
  "Retain visibility until the exit delay expires; cancel on reentry/unmount."
  [visible? delay-ms]
  (let [[retained? set-retained!] (r/use-state visible?)]
    (r/use-effect
     (fn []
       (if visible?
         (do (set-retained! true) nil)
         (let [timer (js/setTimeout #(set-retained! false) delay-ms)]
           #(js/clearTimeout timer))))
     [visible? delay-ms])
    (or visible? retained?)))

(defn use-delayed-hide
  "Return [visible? show! hide!]. Show immediately, hide after a cancellable delay."
  [delay-ms]
  (let [[visible? set-visible!] (r/use-state false)
        *timer (r/use-ref nil)
        cancel! #(when-let [timer (.-current *timer)]
                   (js/clearTimeout timer)
                   (set! (.-current *timer) nil))
        show! #(do (cancel!) (set-visible! true))
        hide! #(do (cancel!)
                   (set! (.-current *timer)
                         (js/setTimeout
                           (fn [] (set! (.-current *timer) nil) (set-visible! false))
                           delay-ms)))]
    (r/use-effect (fn [] cancel!) [])
    [visible? show! hide!]))
