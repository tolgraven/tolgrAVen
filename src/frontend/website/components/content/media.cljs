(ns website.components.content.media
  "Paired public rendition metadata is supplied by the content owner."
  (:require [website.react :as r]))

(def asset-context (r/create-context {}))

(def readiness-schema
  [:map [:ready! fn?] [:failed! fn?]
   [:generations [:map-of :string [:vector [:tuple :string [:int {:min 1}] [:int {:min 0}]]]]]])
(def readiness-context (r/create-context nil))

(defn readiness-attrs [src readiness]
  (cond-> {}
    (:ready! readiness) (assoc :on-visible-ready (fn [_] ((:ready! readiness) src)))
    (:failed! readiness) (assoc :on-error (fn [_] ((:failed! readiness) src)))))

(defn preview-key [src readiness] [src (get-in readiness [:generations src] 0)])
