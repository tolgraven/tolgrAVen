(ns tolgraven.modules.page-render.module
  (:require [tolgraven.ssr.local-render :as render]))

(def spec
  {:id :page-render
   :schema [:map [:pair! fn?]]
   :pair! render/pair!})
