(ns website.modules.page-capture.module
  (:require [website.ssr.capture :as capture]))

(def spec
  {;; A site capability dependency, without acquiring renderer code in the UI.
   :server {:requires #{:page-render}}
   :schema [:map [:pair! fn?] [:stop! fn?]]
   :pair! capture/pair!
   :stop! capture/stop!})
