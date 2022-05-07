(ns ^:figwheel-no-load tolgraven.app
  (:require
    [tolgraven.core :as core]
    [cljs.spec.alpha :as s]
    [expound.alpha :as expound]
    ; [re-frisk-remote.core :as re-frisk]
    [devtools.core :as devtools]))

(extend-protocol IPrintWithWriter
  js/Symbol
  (-pr-writer [sym writer _]
    (-write writer (str "\"" (.toString sym) "\""))))

(set! s/*explain-out* expound/printer)

(enable-console-print!)

(devtools/install!)

; (re-frisk/enable {:ext_height 1000 :ext_width 1200}) ;nicer to have toggleable here no?
; (re-frisk/enable-re-frisk-remote!) ;nicer to have toggleable here no?
; (re-frisk/start-re-frisk-remote!)
; can also disable events with {:events? false}
; couldn't get re-frisk-remote working for the life of me, hmm

(core/init!)
