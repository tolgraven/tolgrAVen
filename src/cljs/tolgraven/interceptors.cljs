(ns tolgraven.interceptors
  (:require
   [re-frame.core :as rf]
   [re-frame.std-interceptors :as rf-std]
   ; [differ.core :as differ]
   [tolgraven.util :as util]))

(def debug (when ^boolean js/goog.DEBUG rf/debug)) ; doesnt shut off in prod tho so, wtf

; TODO make clean way to persist whatever by putting
; an effect in event w just db path, which gets mirrored on server side
; then chuck it in std interceptors attached to everything?
; (guess same as local-storage thingy)
; then again, a let w computed value and just mirror that...
; but auto path would be main thing.
; also be careful with these things heh surely this was what was overwriting my shit before
; when wouldnt get comments etc through to firebase with write haha.
;
; seems more reasonable to just stick to events for eeet :P
(def persist-id-counters
  (rf/->interceptor
   :id    :persist-id-counters
   :after (fn [ctx]
            (when-let [counters (-> ctx :coeffects :db :state :id-counters)]
              (assoc-in ctx [:effects :firebase/write]
                      {:path [:id-counters]
                       :value counters
                       :on-failure [:diag/new :warning "ID counters"
                                    "Couldn't be backed up. Run for your life."]})))))
; then an interceptor tacked-onto those being injected and
; adding a firebase/write event?
; but for now just extra evt
; 
(def warn-side-effects ;from kee-frame guy. should rather inject an event to queue tho...
  (rf/->interceptor
   :id     :warn-side-effects
   :after  (fn [ctx]
             (let [effects (rf/get-effect ctx)
                   event (rf/get-coeffect ctx :event)]
               (when (seq effects)
                 (util/log :warning "SIDE EFFECTS" (str "Event " event " caused " effects)))
               ctx))))

(defonce shadow-db (atom {}))

(def state-soft
  (rf/->interceptor
   :id    :fuzzy-copy
   :after (fn [ctx]
            (let [orig-db (get-in ctx [:coeffects :db])
                  path (get-in orig-db [:state :soft-path]) ; or could have multiple but maybe nah
                  new-db (rf/get-effect ctx :db :not-found)
                  orig-at-path (get-in orig-db path)
                  new-at-path (get-in new-db path)]
              ; figure out what's changed in which direction
              ; use differ
              ; if added or changed we can just assoc.
              ; if removed we must put in a dispatch-later (no bc might overwrite existing)
              ; :later/dispatch instead.
              ; or an fx that runs setTimeout... argh nope no access to saving app-db inside
              ; 
              ; also [at-path display-state] latter takes value of the css class to apply
              ; so stable is nil, disappearing is "closing" or whatever
              (assoc-in ctx (into [:effects :db] path) 
                        :fart)))))

; like what makes this necessary is we've now got variants of same logic
; in swapper and buncha components, slightly different each time.
; Not a massive deal if only doing it for special ones but want for all.
; Jarring when all is smooth except for disappearances.
;
; major issue is react normally to equivalent changes of value
; like a tab key going :main to :detail.
; or it would slooooow down nav haha.
; meanwhile using state and not content heavy content or similar should
; men it's pretty lightweight.
