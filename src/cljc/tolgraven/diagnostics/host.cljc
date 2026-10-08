(ns tolgraven.diagnostics.host
  #?(:dev (:require [tolgraven.dev-console.views :as console])))

(defn <console> []
  #?(:dev [console/<console>]
     :default nil))
