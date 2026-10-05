(ns tolgraven.components.timer
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.react :as react]))

(defc <timeout>
  "Call f after mount, cancelling on unmount. Renders no DOM and is safe in Node."
  [f :- fn? milliseconds :- [:and number? [:>= 0]]]
  (react/use-effect
   (fn []
     (let [timer (js/setTimeout f milliseconds)]
       #(js/clearTimeout timer))) #js [])
  nil)

(defn use-delayed-visible?
  "Retain visibility until the exit delay expires; cancel on reentry/unmount."
  [visible? delay-ms]
  (let [[retained? set-retained!] (react/use-state visible?)]
    (react/use-effect
     (fn []
       (if visible?
         (do (set-retained! true) js/undefined)
         (let [timer (js/setTimeout #(set-retained! false) delay-ms)]
           #(js/clearTimeout timer))))
     #js [visible? delay-ms])
    (or visible? retained?)))

(defn use-delayed-hide
  "Return [visible? show! hide!]. Show immediately, hide after a cancellable delay."
  [delay-ms]
  (let [[visible? set-visible!] (react/use-state false)
        *timer (react/use-ref nil)
        cancel! #(when (.-current *timer) (js/clearTimeout (.-current *timer)))
        show! #(do (cancel!) (set-visible! true))
        hide! #(do (cancel!)
                   (set! (.-current *timer) (js/setTimeout (fn [] (set-visible! false)) delay-ms)))]
    (react/use-effect (fn [] cancel!) #js [])
    [visible? show! hide!]))
