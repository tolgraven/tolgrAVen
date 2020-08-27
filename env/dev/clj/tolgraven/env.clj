(ns tolgraven.env
  (:require
    [selmer.parser :as parser]
    [clojure.tools.logging :as log]
    [tolgraven.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init
   (fn []
     (parser/cache-off!)
     (log/info "\n-=[tolgraven started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "\n-=[tolgraven has shut down successfully]=-"))
   :middleware wrap-dev})
