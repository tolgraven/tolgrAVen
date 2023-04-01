(ns ^:figwheel-no-load tolgraven.app
  (:require
    [tolgraven.core :as core]
    [cljs.spec.alpha :as s]
    [expound.alpha :as expound]
    [day8.re-frame-10x :as r10x]
    [portal.web :as p]
    ; [re-frisk-remote.core :as re-frisk]
    [devtools.core :as devtools]))

(day8.re-frame-10x/show-panel! false) ; auto hide by default since often test in private window or on mobile where cant press ctrl-h...

(extend-protocol IPrintWithWriter ; get interactive prints in console
  js/Symbol
  (-pr-writer [sym writer _]
    (-write writer (str "\"" (.toString sym) "\""))))

(enable-console-print!)
; (devtools/install!) ; no need when use preload

(set! s/*explain-out* expound/printer)


(defn portal [] ; instead open by ctrl alt x something
  (p/open))

(add-tap #'p/submit)
(tap> :booted)
; (re-frisk/enable {:ext_height 1000 :ext_width 1200}) ;nicer to have toggleable here no?
; (re-frisk/enable-re-frisk-remote!) ;nicer to have toggleable here no?
; (re-frisk/start-re-frisk-remote!)
; can also disable events with {:events? false}
; couldn't get re-frisk-remote working for the life of me, hmm

(core/init!)
