(ns tolgraven.modules.maps.module
  (:require [react-leaflet]
            [leaflet]))

;; A shared code dependency keeps the mapping libraries out of :main.
(def spec
  {:id :maps
   :styles ["/css/tolgraven/modules/maps.min.css"]})
