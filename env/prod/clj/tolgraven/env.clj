(ns tolgraven.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[tolgraven started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[tolgraven has shut down successfully]=-"))
   :middleware identity})
