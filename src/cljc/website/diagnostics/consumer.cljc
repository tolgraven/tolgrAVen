(ns website.diagnostics.consumer
  #?(:dev (:require [website.dev.consumer :as dev])))
(defn subscribe! [query reaction]
  #?(:dev (dev/subscribe! query reaction) :default reaction))
(defn render! [definition args render]
  #?(:dev (dev/render! definition args render) :default (render)))
