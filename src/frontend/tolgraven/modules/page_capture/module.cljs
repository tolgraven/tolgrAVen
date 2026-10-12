(ns tolgraven.modules.page-capture.module
  (:require [tolgraven.ssr.capture :as capture]))

(def spec
  {:id :page-capture
   :schema [:map [:pair! fn?] [:stop! fn?]]
   :pair! capture/pair!
   :stop! capture/stop!})
