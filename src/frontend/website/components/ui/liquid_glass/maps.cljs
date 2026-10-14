(ns website.components.ui.liquid-glass.maps
  "Owned, detached canvas encoding and a bounded cache of immutable PNG maps."
  (:require [website.components.ui.liquid-glass.optics :as optics]))

(def cache-capacity 12)
(def cache-byte-budget (* 8 1024 1024))
(defonce *cache (atom {:entries {}, :order [], :bytes 0}))

(defn clear-cache! [] (reset! *cache {:entries {}, :order [], :bytes 0}))
(defn cache-info [] (select-keys @*cache [:order :bytes]))

(defn- retain [cache key value]
  (let [previous (get-in cache [:entries key])
        size (* 2 (+ (count (:displacement value)) (count (:highlight value))))
        cache' (-> cache
                   (assoc-in [:entries key] (assoc value :bytes size))
                   (update :order #(conj (filterv (fn [entry] (not= entry key)) %) key))
                   (update :bytes #(+ (- % (:bytes previous 0)) size)))]
    (loop [remaining cache']
      (if (or (> (count (:order remaining)) cache-capacity)
              (> (:bytes remaining) cache-byte-budget))
        (let [oldest (first (:order remaining))
              bytes (get-in remaining [:entries oldest :bytes])]
          (recur (-> remaining
                     (update :entries dissoc oldest)
                     (update :order #(subvec % 1))
                     (update :bytes - bytes))))
        remaining))))

(defn- encode! [width height data]
  ;; This canvas is an image encoder, never React-owned rendered DOM.
  (let [canvas (.createElement js/document "canvas")]
    (set! (.-width canvas) width)
    (set! (.-height canvas) height)
    (.putImageData (.getContext canvas "2d") (js/ImageData. data width height) 0 0)
    (.toDataURL canvas "image/png")))

(defn acquire! [key cache?]
  (if-let [cached (when cache? (get-in @*cache [:entries key]))]
    (do (swap! *cache update :order #(conj (filterv (fn [entry] (not= entry key)) %) key))
        cached)
    (let [{:keys [width height maximum displacement highlight]} (optics/field key)
          result {:displacement (encode! width height displacement)
                  :highlight (encode! width height highlight)
                  :maximum maximum
                  :pixels (* width height)}]
      (when cache? (swap! *cache retain key result))
      (or (when cache? (get-in @*cache [:entries key])) result))))

(defn schedule! [callback]
  (if (exists? js/requestIdleCallback)
    (let [id (js/requestIdleCallback callback #js {:timeout 150})]
      #(js/cancelIdleCallback id))
    (let [id (js/setTimeout callback 0)]
      #(js/clearTimeout id))))
