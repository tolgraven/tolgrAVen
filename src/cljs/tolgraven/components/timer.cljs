(ns tolgraven.components.timer
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [react :as react]))

(defc <timeout>
  "Call f after mount, cancelling on unmount. Renders no DOM and is safe in Node."
  [f milliseconds]
  (react/useEffect
   (fn []
     (let [timer (js/setTimeout f milliseconds)]
       #(js/clearTimeout timer))) #js [])
  nil)

(defn use-delayed-visible?
  "Retain visibility until the exit delay expires; cancel on reentry/unmount."
  [visible? delay-ms]
  (let [[retained? set-retained!] (react/useState visible?)]
    (react/useEffect
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
  (let [[visible? set-visible!] (react/useState false)
        *timer (react/useRef nil)
        cancel! #(when (.-current *timer) (js/clearTimeout (.-current *timer)))
        show! #(do (cancel!) (set-visible! true))
        hide! #(do (cancel!)
                   (set! (.-current *timer) (js/setTimeout (fn [] (set-visible! false)) delay-ms)))]
    (react/useEffect (fn [] cancel!) #js [])
    [visible? show! hide!]))
