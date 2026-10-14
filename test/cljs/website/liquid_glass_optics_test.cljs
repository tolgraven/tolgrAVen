(ns website.liquid-glass-optics-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.react.server :as server]
            [website.components.ui.liquid-glass :as glass]
            [website.components.ui.liquid-glass.optics :as optics]))

(def base-field
  {:width 100
   :height 40
   :radius 10
   :bezel 6
   :thickness 30
   :shape :rounded-rect
   :points [[0 0] [1 0] [1 1] [0 1]]
   :profile :squircle
   :index 1.5
   :resolution 1
   :max-pixels 65536
   :max-side 1024
   :light-angle -60
   :light-focus 2})

(deftest snell-profile-properties
  (doseq [profile [:convex :squircle :concave :lip]]
    (let [table (optics/profile-table profile 1.5 30)]
      (is (= 257 (count (:values table))))
      (is (every? optics/finite? (:values table)))
      (is (pos? (:maximum table)))
      (doseq [t [0 0.1 0.5 0.9 1]]
        (is (= 0 (optics/displacement profile 1 30 t))
            "An index equal to air must not refract")
        (is (= 0 (optics/displacement profile 1.5 0 t))
            "Zero thickness must not displace"))))
  (is (pos? (optics/displacement :convex 1.5 30 0.5)))
  (is (neg? (optics/displacement :concave 1.5 30 0.5)))
  (is (thrown? js/Error (optics/profile-table (constantly js/NaN) 1.5 30)))
  (is (thrown? js/Error (optics/profile-table (constantly js/Infinity) 1.5 30))))

(deftest signed-shape-distances-and-normals
  (is (= [20 0 -1] (optics/rounded-distance 100 40 10 50 20)))
  (is (= [2 1 0] (optics/rounded-distance 100 40 10 2 20)))
  (is (= [2 -1 0] (optics/rounded-distance 100 40 10 98 20)))
  (is (neg? (first (optics/rounded-distance 100 40 10 0 0))))
  (is (pos? (first (optics/ellipse-distance 100 40 50 20))))
  (is (neg? (first (optics/ellipse-distance 100 40 0 0))))
  (doseq [x [1 25 50 75 99], y [1 10 20 30 39]]
    (is (every? optics/finite? (optics/ellipse-distance 100 40 x y))))
  (let [square [[0 0] [100 0] [100 40] [0 40]]
        notch [[0 0] [100 0] [100 40] [60 40] [60 10] [40 10] [40 40] [0 40]]]
    (is (pos? (first (optics/polygon-distance square 50 20))))
    (is (neg? (first (optics/polygon-distance square 110 20))))
    (is (neg? (first (optics/polygon-distance notch 50 30))))
    (is (pos? (first (optics/polygon-distance notch 20 30))))
    (doseq [x [-5 0 1 25 50 99 100 105], y [-5 0 1 15 20 39 40 45]]
      (is (< (abs (- (first (optics/polygon-distance square x y))
                     (first (optics/rounded-distance 100 40 0 x y)))) 0.00001)))))

(deftest bounded-raster-allocations
  (doseq [[width height] [[1 1] [800 220] [10000 10000] [1000000000 1] [1 1000000000]]
          resolution [0.1 0.5 2]
          budget [256 65536]]
    (let [[w h] (optics/raster-size width height resolution budget 1024)]
      (is (<= (* w h) budget))
      (is (<= 1 w 1024))
      (is (<= 1 h 1024))))
  (is (thrown? js/Error (optics/raster-size 0 40 1 65536 1024)))
  (is (thrown? js/Error (optics/raster-size js/Infinity 40 1 65536 1024))))

(deftest displacement-map-is-symmetric-and-flat-in-the-interior
  (let [{:keys [width height displacement highlight]} (optics/field base-field)
        red (fn [x y] (aget displacement (* 4 (+ x (* y width)))))
        green (fn [x y] (aget displacement (inc (* 4 (+ x (* y width))))))]
    (is (= 128 (red 50 20)))
    (is (= 128 (green 50 20)))
    (is (> (red 1 20) 128))
    (is (< (red 98 20) 128))
    (is (= 256 (+ (red 1 20) (red 98 20))))
    (is (= (* width height 4) (.-length displacement) (.-length highlight)))
    (is (some pos? (map #(aget highlight (+ 3 (* 4 %))) (range (* width height)))))
    ;; The component transfer maps the integer neutral to exactly .5. SVG's
    ;; displacement equation then leaves the flat interior stationary.
    (is (= 0 (- (+ (* (/ 128 255) (/ 255 254)) (- (/ 1 254))) 0.5)))))

(deftest every-shape-profile-produces-bounded-fields
  (doseq [shape [:rounded-rect :capsule :ellipse :polygon]
          profile [:convex :squircle :concave :lip]]
    (let [result (optics/field (assoc base-field :shape shape :profile profile))]
      (is (optics/finite? (:maximum result)))
      (is (<= (* (:width result) (:height result)) 65536)))))

(deftest public-spec-contract-and-production-allocation-guards
  (let [schema (dsl/->malli glass/spec-schema)]
    (is (m/validate schema {}))
    (is (m/validate schema {:props {:class "card"}, :opacity 0.4, :shape :ellipse}))
    (is (m/validate schema {:shape :polygon, :points [[0 0] [1 0] [0.5 1]], :outset ["1rem" "2rem" "1rem" "0"]}))
    (doseq [spec [{:opacity 2} {:opacity js/NaN} {:radius "12px"}
                 {:mode :unknown} {:shape :polygon, :points [[0 0] [1 1]]}
                 {:resolution 0} {:max-pixels 2000000} {:index 0.5}]]
      (is (not (m/validate schema spec)) (str spec))))
  (let [settings (glass/settings {:max-pixels js/Infinity, :max-side 50000, :opacity -2, :scale 100})]
    (is (= 65536 (:max-pixels settings)))
    (is (= 2048 (:max-side settings)))
    (is (= 0 (:opacity settings)))
    (is (= 4 (:scale settings)))))

(deftest node-ssr-renders-the-same-interactive-children-without-browser-apis
  (let [html (server/render-to-string
               [glass/<liquid-glass> {:props {:id "ssr-glass"}, :opacity 0.4, :outset "1rem"}
                [:button {:type "button"} "Keep working"]
                [:input {:default-value "Typed value"}]
                [:a {:href "/blog"} "Blog"]])]
    (is (.includes html "id=\"ssr-glass\""))
    (is (.includes html "data-glass-mode=\"frosted\""))
    (is (.includes html "Keep working"))
    (is (.includes html "Typed value"))
    (is (.includes html "href=\"/blog\""))
    (is (.includes html "opacity:0.4"))
    (is (not (.includes html "data:image")))
    (is (not (.includes html "<filter")))))
