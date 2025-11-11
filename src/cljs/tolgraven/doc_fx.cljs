(ns tolgraven.doc-fx
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojure.string :as string]
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

(rf/reg-event-fx :document/set-title!
  (fn [{:keys [db]} [_ match]]
    {:document/set-title
     (str (some-> (or (get-in db [:state :document :title])   ; specifically set (and cleared!!) by a component and/or its controller
                      (some-> match :parameters :path vals first
                              (string/replace #"-" " ")))     ; backup: use path-params as base
                  (str " - "))
          (some-> match :data :name name string/capitalize (str " ")) ; category
          " - "   (get-in db [:content :document :title]))})) ; site title comes last


(rf/reg-fx :document/set-title
  (fn [title]
    (set! js/document.title title)))


(rf/reg-event-fx :window/fullscreen!
  (fn [{:keys [db]} [_ fullscreen?]]  ;TODO want further info in title, like blog post title...
    {:db (assoc-in db [:state :window :fullscreen?] fullscreen?)
     :window/fullscreen! fullscreen?}))

(rf/reg-fx :window/fullscreen!
  (fn [fullscreen?]
    (if fullscreen?
      (if-let [request-method (or js/document.documentElement.requestFullScreen
                                  js/document.documentElement.webkitRequestFullScreen
                                  js/document.documentElement.mozRequestFullScreen
                                  js/document.documentElement.msRequestFullscreen)]
        (.call request-method js/document.documentElement)
        (rf/dispatch [:log/write! :error "Can't go fullscreen" "No method found"]))
      (if-let [request-method (or js/document.exitFullScreen
                                  js/document.webkitCancelFullScreen
                                  js/document.mozCancelFullScreen
                                  js/document.msCancelFullscreen)]
        (.call request-method js/document)
        (rf/dispatch [:log/write! :error "Can't exit fullscreen" "No method found"])))))

(rf/reg-event-fx :focus-element
  (fn [_ [_ elem-id]]
    {:focus-to-element elem-id}))
(rf/reg-fx :focus-to-element
  (fn [elem-id] 
    (r/after-render #(js/setTimeout
                      (fn []
                        (some-> elem-id util/elem-by-id .focus))
                      0))))
    ; (some-> (util/elem-by-id elem-id) .focus)))


(rf/reg-event-fx :->css-var!
  (fn [{:keys [db]} [_ var-name value]]
    {:db (assoc-in db [:state :css-var var-name] value)
     :set-css-var [var-name value]}))

(rf/reg-fx :set-css-var
  (fn [[var-name value]]
    (util/->css-var var-name value)))


(rf/reg-event-fx :run-highlighter!
 (fn [_ [_ elem]]
   {:run-highlighter-fx! elem}))

(rf/reg-fx :run-highlighter-fx!
 (fn [elem]
   (util/run-highlighter! "pre" elem)))

