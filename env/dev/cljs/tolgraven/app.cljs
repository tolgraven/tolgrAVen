(ns tolgraven.app
  (:require
    [tolgraven.core :as core]
    [cljs.spec.alpha :as s]
    [expound.alpha :as expound]
    [day8.re-frame-10x :as r10x]
    [portal.web :as p]
    [re-frisk.core :as re-frisk]
    [devtools.core :as devtools]))

(defn configure!
  []
  (extend-protocol IPrintWithWriter ; get interactive prints in console
      symbol
      (-pr-writer [sym writer _]
          (-write writer (str "\"" (.toString sym) "\""))))

  (enable-console-print!)

  (set! s/*explain-out* expound/printer))

(defn ^:export init!
  []
  (configure!)

  (core/init!)
  
  (r10x/show-panel! false) ; auto hide by default since often test in private window or on mobile where cant press ctrl-h...
  ; (devtools/install!) ; no need when use preload
  (re-frisk/enable {:ext_height 1000 :ext_width 1200}) ;nicer to have toggleable here no?
  ; (re-frisk/enable-re-frisk-remote!) ;nicer to have toggleable here no?
  ; can also disable events with {:events? false}
  ; couldn't get re-frisk-remote working for the life of me, hmm

  (add-tap #'p/submit)
  (tap> :booted)
  (p/open))

