(ns website.diagnostics.host
  #?(:dev (:require [website.dev-console.views :as console])))

(defn <console> []
  #?(:dev [console/<console>]
     :default nil))
