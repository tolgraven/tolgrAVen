(ns website.modules.page-render.module
  (:require [website.ssr.local-render :as render]))

(def spec
  {:schema [:map [:pair! fn?]]
   :pair! render/pair!})
