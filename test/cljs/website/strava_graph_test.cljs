(ns website.strava-graph-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.modules.strava.views :as views]
            [website.test-support :as support]))

(deftest native-graphs-paint-constant-series-and-retain-varying-coordinates
  (async done
    (let [element (.createElement js/document "div")]
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (try
                (await! (support/render! root
                          [:canvas {:style {:width "20rem", :height "5rem"}}]))
                (let [canvas (.querySelector element "canvas")
                      context (.getContext canvas "2d")
                      original-line-to (.-lineTo context)
                      *coordinates (atom [])
                      cursor (r/atom [-100 -100])]
                  (try
                    (set! (.-lineTo context)
                      (fn [x y]
                        (swap! *coordinates conj [x y])
                        (.call original-line-to context x y)))
                    (doseq [series [[82 82 82 82 82 82] [0 0 0] [-12 -12 -12]]]
                      (reset! *coordinates [])
                      (views/draw-graph canvas "Constant" series cursor)
                      (let [width (.-width canvas)
                            height (.-height canvas)
                            pixels (.-data (.getImageData context 0 0 width height))]
                        (is (and (pos? width) (pos? height)))
                        (is (= (count series) (count @*coordinates)))
                        (is (every? (fn [[x y]]
                                      (and (js/Number.isFinite x)
                                           (= (/ height 2) y)))
                                    @*coordinates)
                            "Equal-domain samples form a finite centered line")
                        (is (some #(pos? (aget pixels %)) (range 3 (.-length pixels) 4))
                            "The actual native canvas contains painted pixels")))
                    (reset! *coordinates [])
                    (views/draw-graph canvas "Varying" [10 20 15] cursor)
                    (let [width (.-width canvas)
                          height (.-height canvas)]
                      (is (= [[0 height] [(* width (/ 1 3)) 0]
                              [(* width (/ 2 3)) (/ height 2)]]
                             @*coordinates)
                          "Ordinary series retain their original rescaling"))
                    (finally (set! (.-lineTo context) original-line-to))))
                (finally (support/unmount! root)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (.remove element) (done)))))))
