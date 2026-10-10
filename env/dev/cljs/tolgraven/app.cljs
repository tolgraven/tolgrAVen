(ns tolgraven.app
  (:require
    [tolgraven.core :as core]
    [tolgraven.dev-console.capture :as diagnostics]
    [cljs.spec.alpha :as s]
    ;[portal.web :as p]
    [expound.alpha :as expound]))

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

  (diagnostics/bootstrap!)
  (core/init!)
  
  ;(add-tap #'p/submit)
  (tap> :booted)
  #_(p/open))
