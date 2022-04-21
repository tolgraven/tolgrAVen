(ns tolgraven.doc-fx
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [tolgraven.util :as util]))

; a fancier version would stash both fn and args in db
; allow to modify queued call instead of cancel-and-anew
; or line something up but not ms, instead looking for a piece of data?
; but makes more sense w event version then... hook on events.
; this is really only for working with js objs directly, keeping at least
; some track of what's happening.

(rf/reg-event-fx :run-in!
  (fn [_ [_ id-key ms f & args]]
    {:dispatch-later
     {:ms ms
      :dispatch (into [:run-fn! id-key f] args)}}))

(rf/reg-event-fx :run-fn!
  (fn [_ [_ id-key f & args]]
    {:run-fn-fx! [id-key f args]}))

(rf/reg-fx :run-fn-fx!
  (fn [[id-key f args]]
    (if args
      (apply f args)
      (f))))

; THIS VERSION DROPS SUBSEQ CALLS IF SOMETHING ALREADY THERE
; (rf/reg-event-fx :run-in! ; trying to figure out why'd be bad idea.
; ; certainly would be if over-used (js cb hell) but for like, what would otherwise be:
; ; define evt, define second evt, dispatch-later to second.
; ; just dispatch to a generic evt that takes a fn. basically the :get of events I suppose, bad in long run
; ; but can do some things easy quick - dont have to define stuff for every little document/window etc callout.
; ; and passing an id we can still see what is what so..  plus get to close over stuff etc yada.
;   (fn [{:keys [db]} [_ id-key ms f & args]]
;     ; (when-not (some (-> db :state :run-fn set) [id-key]) ;something's already queued to run
;     ;   )
;     {:db (update-in db [:state :run-fn] conj id-key)
;        :dispatch-later
;        {:ms ms
;         :dispatch (into [:run-fn! id-key f] args)}}))
;     ; {:dispatch-throtle
;     ;  {:id id-key
;     ;   :window-duration ms
;     ;   :trailing? true
;     ;   :dispatch (into [:run-fn! id-key f] args)}}))
   
; (rf/reg-event-fx :run-in-overwrite! ; THIS VERSION DROPS EARLIER CALLS (so needs additional seq-id to keep track?)
;                  ; welp needed anyways really or queued calls would be reactivated...
;   (fn [{:keys [db]} [_ id-key ms f & args]]
;     (when-not (some (-> db :state :run-fn set) [id-key])
;       {:db (update-in db [:state :run-fn] conj id-key)
;        :dispatch-later
;        {:ms ms
;         :dispatch (into [:run-fn! id-key f] args)}})))

; (rf/reg-event-fx :stop-run-fn
;   (fn [{:keys [db]} [_ id-key]]
;     {:db (update-in db [:state :run-fn] #(-> % set (disj id-key)))}))

; (rf/reg-event-fx :run-fn!
;   (fn [{:keys [db]} [_ id-key f & args]]
;     (when (some (-> db :state :run-fn set) [id-key])
;       {:db (update-in db [:state :run-fn] #(-> % set (disj id-key)))
;        :run-fn-fx! [id-key f args] })))


(rf/reg-fx :document/set-title
  (fn [title]
    (set! js/document.title title)))

(rf/reg-event-fx :focus-element
  (fn [_ [_ elem-id]]
    {:focus-to-element elem-id}))
(rf/reg-fx :focus-to-element
  (fn [elem-id] 
    (r/after-render #(some-> (util/elem-by-id elem-id) .focus))))


(rf/reg-event-fx :->css-var!
  (fn [{:keys [db]} [_ var-name value]]
    {:db (assoc-in db [:state :css-var var-name] value)
     :set-css-var [var-name value]}))

(rf/reg-fx :set-css-var
  (fn [[var-name value]]
    (util/->css-var var-name value)))

(rf/reg-event-fx :scroll/by
 (fn [_ [_ value & [in-elem-id]]] ; rem (should handle % too tho?), id of container..
   {:scroll/by [value in-elem-id]}))
(rf/reg-fx :scroll/by
 (fn [[value & [in-elem-id]]]
   (util/scroll-by value in-elem-id)))

(rf/reg-event-fx :scroll/to
 (fn [db [_ id delay-ms]]
   (if delay-ms
     {:dispatch-later {:ms delay-ms
                       :dispatch [:scroll/to id]}}
     (when-not (get-in db [:state :scroll :block])
       {:scroll/to id}))))
(rf/reg-fx :scroll/to
 (fn [id]
   (util/scroll-to id)))

(rf/reg-event-fx :scroll/px
 (fn [db [_ px delay-ms]]
   (when-not (get-in db [:state :scroll :block])
     {:scroll/px px})))
(rf/reg-fx :scroll/px
 (fn [px]
   (util/scroll-to-px px)))


(rf/reg-event-fx :run-highlighter!
 (fn [_ [_ elem]]
   {:run-highlighter-fx! elem}))

(rf/reg-fx :run-highlighter-fx!
 (fn [elem]
   (util/run-highlighter! "pre" elem)))

