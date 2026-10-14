(ns website.liquid-glass-demo
  (:require [website.react :as r]
            [website.modules.experiments.liquid-glass :as demo]))

(defonce *root (atom nil))
(defn init! []
  (when-not @*root (reset! *root (r/create-root (.getElementById js/document "app"))))
  (r/render @*root [demo/<playground>]))
