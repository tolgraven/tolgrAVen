(ns website.components.content.layout
  "Persisted content geometry is data; runtime measurements stay in owned adapters."
  (:require [clojure.string :as string]
            [website.react :as r]))

(def dimension-pattern #"^(?:auto|fit|[0-9]+(?:\.[0-9]+)?(?:rem|em|px|%))$")
(def dimension [:re dimension-pattern])
(def schema [:map [:?width dimension] [:?height dimension]
                  [:?align [:enum "left" "center" "right"]]
                  [:?flow [:enum "block" "left" "right"]]])
(def fields [:width :height :align :flow])
(def attribute-fields [:layout-width :layout-height :layout-align :layout-flow])
(def geometry-context (r/create-context {}))

(defn geometry [props]
  (into {} (keep (fn [[field attribute]] (when (contains? props attribute) [field (get props attribute)])))
        (map vector fields attribute-fields)))

(defn attributes [geometry]
  (into {} (keep (fn [[field attribute]] (when (contains? geometry field) [attribute (get geometry field)])))
        (map vector fields attribute-fields)))

(defn style [{:keys [width height align flow]}]
  (cond-> {:max-width "100%"}
    (and width (not= "fit" width)) (assoc :width width)
    (and height (not (#{"auto" "fit"} height))) (assoc :height height)
    (= flow "left") (assoc :float "left")
    (= flow "right") (assoc :float "right")
    (= align "center") (assoc :margin-inline "auto")
    (= align "right") (assoc :margin-inline-start "auto")))

(defn resized
  "Preserve the user's chosen unit; auto/fit starts with a relative width."
  [dimension pixels {:keys [rem em parent]}]
  (let [unit (or (second (re-find #"(rem|em|px|%)$" (or dimension ""))) "rem")
        divisor (case unit "rem" rem "em" em "%" (/ parent 100) 1)
        amount (/ (max 1 pixels) (max 0.01 (or divisor 16)))]
    (str (/ (js/Math.round (* 100 amount)) 100) unit)))
