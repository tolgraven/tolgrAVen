(ns tolgraven.components.timer
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.react :as react]))

(defc <timeout>
  "Call f after mount, cancelling on unmount. Renders no DOM and is safe in Node."
  [f :- fn? milliseconds :- [:and number? [:>= 0]]]
  :let [*callback (atom f)]
  ;; Keep the mount deadline, while callbacks come from the latest committed
  ;; arguments. Returning f here would accidentally declare it as cleanup.
  (react/use-layout-effect (fn [] (reset! *callback f) nil) [f])
  (react/use-effect
   (fn []
     (let [timer (js/setTimeout #(@*callback) milliseconds)]
       #(js/clearTimeout timer))) [])
  nil)

(defn use-delayed-visible?
  "Retain visibility until the exit delay expires; cancel on reentry/unmount."
  [visible? delay-ms]
  (let [[retained? set-retained!] (react/use-state visible?)]
    (react/use-effect
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
  (let [[visible? set-visible!] (react/use-state false)
        *timer (react/use-ref nil)
        cancel! #(when-let [timer (.-current *timer)]
                   (js/clearTimeout timer)
                   (set! (.-current *timer) nil))
        show! #(do (cancel!) (set-visible! true))
        hide! #(do (cancel!)
                   (set! (.-current *timer)
                         (js/setTimeout
                           (fn [] (set! (.-current *timer) nil) (set-visible! false))
                           delay-ms)))]
    (react/use-effect (fn [] cancel!) [])
    [visible? show! hide!]))
