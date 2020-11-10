(ns tolgraven.doc-fx
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [tolgraven.util :as util]))


; (rf/reg-event-fx :run-in 
;   (fn [_ [_ id-key ms f & args]]
;     {:dispatch-later
;      {:ms ms
;       :dispatch [:run-fn (into [id-key f] args)]}}))

; (rf/reg-event-fx :run-fn
;   (fn [_ [_ id-key f & args]]
;     (apply f args)
;     nil))

(rf/reg-event-fx :run-in! ; trying to figure out why'd be bad idea.
; certainly would be if over-used (js cb hell) but for like, what would otherwise be:
; define evt, define second evt, dispatch-later to second.
; just dispatch to a generic evt that takes a fn. basically the :get of events I suppose, bad in long run
; but can do some things easy quick - dont have to define stuff for every little document/window etc callout.
; and passing an id we can still see what is what so..  plus get to close over stuff etc yada.
  (fn [_ [_ id-key ms f & args]]
    {:dispatch-later
     {:ms ms
      :dispatch [:run-fn! id-key f args]}}))

(rf/reg-event-fx :run-fn!
  (fn [_ [_ id-key f & args]]
    {:run-fn-fx! [id-key f args]}))

(rf/reg-fx :run-fn-fx!
  (fn [[id-key f & args]]
    (if args
      (apply f args)
      (f))))


(rf/reg-fx :document/set-title
  (fn [title]
    (set! js/document.title title)))

(rf/reg-event-fx :focus-element
  (fn [_ [_ elem-id]]
    ; (r/after-render #(some-> (util/elem-by-id elem-id) .focus))
    {:focus-to-element elem-id}))
(rf/reg-fx :focus-to-element
  (fn [elem-id] 
    (r/after-render #(some-> (util/elem-by-id elem-id) .focus))))


(rf/reg-event-fx :->css-var!
  (fn [_ [_ var-name value]]
    (util/->css-var var-name value)
    nil)) ;avoid it trying to parse heh


(rf/reg-event-fx :scroll/by
 (fn [_ [_ value & [in-elem-id]]] ; rem (should handle % too tho?), id of container..
   {:scroll/by [value in-elem-id]}))
(rf/reg-fx :scroll/by
 (fn [[value & [in-elem-id]]]
   (util/scroll-by value in-elem-id)))

(rf/reg-event-fx :scroll/to
 (fn [_ [_ id & [offset]]]
   {:scroll/to [id offset]}))
(rf/reg-fx :scroll/to
 (fn [[id offset]]
   (util/scroll-to id offset)))

(rf/reg-event-fx :scroll/px
 (fn [_ [_ px]]
   {:scroll/px px}))
(rf/reg-fx :scroll/px
 (fn [px]
   (util/scroll-to-px px)))


(rf/reg-event-fx :run-highlighter!
 (fn [_ [_ elem]]
   (when elem
     {:run-highlighter-fx! elem})))

(rf/reg-fx :run-highlighter-fx!
 (fn [elem]
   (util/run-highlighter! "pre" elem)))

