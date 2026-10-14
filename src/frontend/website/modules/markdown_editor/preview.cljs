(ns website.modules.markdown-editor.preview
  "The content owner acknowledges actual decoded, visible image generations."
  (:require [website.react :as r]))

(defn readiness [owner generations]
  (let [acknowledge! (fn [event src]
                       (doseq [[id attempt generation] (get generations src)]
                         (r/dispatch [event id attempt src owner generation])))]
    {:ready! #(acknowledge! :blog/image-preview-ready %)
     :failed! #(acknowledge! :blog/image-preview-failed %)
     :generations generations}))

(defn assets [uploads]
  (into {} (keep (fn [{:keys [asset]}] (when asset [(:src asset) asset]))) uploads))
