(ns tolgraven.interceptors
  (:require
   [re-frame.core :as rf]
   [re-frame.std-interceptors :as rf-std]
   [tolgraven.util :as util]))

(def debug (when ^boolean goog.DEBUG rf/debug)) ; doesnt shut off in prod tho so, wtf

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
            (assoc-in ctx [:effects :firebase/write]
                      {:path [:id-counters]
                       :value (-> ctx :effects :db :state :id-counters)
                       :on-failure [:diag/new :warning "ID counters"
                                    "Couldn't be backed up. Run for your life."]}))))
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
