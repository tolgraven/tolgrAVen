(ns^:figwheel-no-load tolgraven.app
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

; (re-frisk/enable) ;nicer to have toggleable here no?
; can also disable events with {:events? false}

(core/init!)
