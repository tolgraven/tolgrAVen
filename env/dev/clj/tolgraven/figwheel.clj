(ns tolgraven.figwheel
  ; (:require [figwheel.main :as fw]))
  (:require [figwheel-sidecar.repl-api :as fw]))
  ; #_(:require #_[figwheel-sidecar.repl-api :as ra]))

(defn start-fw []
  #_(fw/start)
  (fw/start-figwheel!))

(defn stop-fw []
  (fw/stop-figwheel!))

(defn cljs []
  (fw/cljs-repl))

