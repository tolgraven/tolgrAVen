(ns website.env
  (:require
    [clojure.tools.logging :as log]
    [website.dev-middleware :refer [wrap-dev]]
    [website.dev.shadow :as shadow-startup]))

(def defaults
  {:development? true
   :init
   (fn []
     (log/info "\n-=[tolgraven started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "\n-=[tolgraven has shut down successfully]=-"))
   :document-ready! shadow-startup/await-document!
   :document-ready? shadow-startup/document-ready?
   :middleware wrap-dev})
