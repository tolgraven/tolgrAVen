(ns website.modules.blog.install
  "Browser cache lifecycle; Node rendering has no disk or live app-db cache."
  #?(:browser (:require [website.modules.blog.cache :as cache])))

(defn cache! []
  #?(:browser (cache/install!) :default nil))
