(ns tolgraven.build.ssr
  (:require [clojure.java.io :as io]))

(defn revision
  {:shadow.build/stage :flush}
  [state]
  ;; Written only after a successful Shadow flush. A dev launcher can remain
  ;; unchanged while its compiled dependencies change, so its mtime is insufficient.
  (let [output (get-in state [:shadow.build/config :output-to])]
    (spit (io/file (str output ".revision")) (str (java.util.UUID/randomUUID))))
  state)
