(ns tolgraven.util
  (:require [re-frame.core :as rf]
            [cljsjs.highlight :as hljs]
            [clojure.string :as string]
            [clojure.walk]))

(defn log "Log to both console and app" ;XXX should add an endpoint to timbre instead.
 ([message] (log :debug message))
 ([level message & messages]
  (let [msg (string/join " " (into [message] messages))]
   (println (string/capitalize (name level)) "\t" message)
   (rf/dispatch [:diag/new level "Log" msg]))))

(defn log-errors [errors]
 (when (seq errors)
  (log :debug errors))) ;debug instead of error, dont want to spam the hud

; (log "kuken")
; (log :error "goddamn")
;; grab shit from cue-db...
;; also extra js stuff.
;; later make lib w this crap.
(defn <-css-var "Get value of CSS variable. Have to apply to dummy div to force calc"
  [var-name & calc]
  (let [var-name (cond-> var-name
                   (not (string/index-of var-name "--")) (str "--" var-name))
        style (js/getComputedStyle js/document.documentElement)]
    ; (js/console.log style) ;uh this revealed this gets fkn hammered like what.
    (if-not calc
      (.getPropertyValue style var-name)
      ; else have some dummy div we literally apply stupid css to with the calced fucker...
      ; goddamn. fix some other time then.
      )))

(defn ->css-var "Set value of CSS variable"
  [var-name value]
  (doto js/document.documentElement.style
        (.setProperty var-name value)))

(defn css-str "string builder"
 [f & args] (str f "(" (string/join ", " args) ")"))
(defn css-arg [& parts] (string/join " " parts))
(defn css-rot [angle] (str "rotate(" angle "deg)"))

(defn cubic-bezier [& args] (apply css-str "cubic-bezier" args))

(defn make-key "Make key easily for React lists..." [& ids] ;could this return the meta straight away instead
 (clojure.string/join "-" (map #(if (keyword? %) (name %) (str %)) ids)))


(defn cs [& names] (string/join " " (filter identity names)))

(defn add-attrs "Put more attrs in something already defined..."
  [component attrs]
  (if (map? (second component))
      (update component 1 merge attrs) ; oh yeah forgot about update-in going inside heh. also not even needed!
      [(first component) attrs (rest component)])) ; eh uh but

(defn deep-merge "Recursively merge maps. If vals are not maps, the last value wins."
 [& values]
 (if (every? map? values)
  (apply merge-with deep-merge values)
  (last values)))

(defn remove-nils "Remove keys with nil or empty seq values, even in nested maps."
  [m]
  (let [f (fn [[k v]] (when (if (coll? v) (seq v) v) [k v]))]
    (clojure.walk/postwalk
     (fn [x] (if (and (map? x) (not (record? x)))
               (into {} (map f x))
               x))
     m)))


(defn at "Take symbol: if value, return it. If ratom, deref it, thereby avoiding nil derefing etc..."
 [val-or-atom & fallback]
 (if (satisfies? IDeref val-or-atom) ;is satisfies equally slow on js? investigate...
  @val-or-atom
  (or val-or-atom fallback)))

(defn elem-by-id [id]
  (.getElementById js/document id))
(defn on-window [event f]
 (js/window.addEventListener event f))
(defn on-document [event f & [opt-map]]
 (js/document.addEventListener event f (clj->js opt-map)))
(defn remove-on-document [event f & [opt-map]]
 (js/document.removeEventListener event f (clj->js opt-map)))

(defn on-event [node event f & [active?]]
 (let [node (if (or (keyword? node) (string? node))
              (elem-by-id (name node))
              node)]
  (.addEventListener node event f active?)))



(defn bounding-rect [e]
 (.getBoundingClientRect (.-target e)))
(defn client-xy [e]
 [(.-clientX e) (.-clientY e)])

(defn elem-in-view? "Returns true when element is visible" [el]
  (let [rect (.getBoundingClientRect el)
        [top bottom] [(.-top rect) (.-bottom rect)]]
    (and (< top js/window.innerHeight) (>= bottom 0))))

(defn rem-to-px "Convert rem to pixels. Doesnt seem like gets quite perfect..."
  [rem-val]
  (-> (js/getComputedStyle js/document.documentElement)
      .-fontSize
      js/parseFloat
      (* rem-val)))

(defn run-highlighter! [el-type & [div-ref]]
  (let [selected (-> (or div-ref js/document)
                     (.querySelectorAll el-type))]
    (doall (map js/hljs.highlightBlock selected))))


(defn scroll-by "Manually scroll view by. But guess need custom for Safar-eye"
  [rem] ; can use css calc stuff for rem -> px?
  (js/window.scrollBy 0 (rem-to-px rem)))

(defn scroll-to "Manual so Safari gets same fn as chrome
                 Plus move page down correctly when open menu etc...
                 There is a CSS offset thing but cant get it working?"
  [id & offset] ; in case css nyah work...
  (.scrollIntoView (elem-by-id id)
                   (clj->js {:behavior :smooth, :block :start})))

(defn observe [div-ref on-view-change & continous?] ;what's with the weird scrolling bug?
  (when (at div-ref) ; well with ratom at least thing will reload, bit extra smudge but most reasonable?
    (let [in-view (atom 0.0)]
      (doto  (js/IntersectionObserver. ;prob needs tearing down for reload or?
              (fn [entries]
                (let [pos (.-intersectionRatio (first entries))
                      val-fn (if continous? identity pos?)] ; but eh still wanna filter repeats
                  ; btw super wasteful should only spit when changes duh
                  (when (not= (val-fn @in-view) (val-fn pos))
                    (on-view-change (val-fn (reset! in-view pos)))))))
        (.observe (at div-ref)))))) ; annoying thing about this one need to manually bind it right?
; how ensure clean these up?


(defn crap []
  (scroll-to "headshot")
  (rem-to-px 13.5) ;217
  (js/window.scrollBy 0 200)
  (js/window.scrollBy 0 -200)
  (scroll-by -13.5)
  (scroll-by 13.5))
