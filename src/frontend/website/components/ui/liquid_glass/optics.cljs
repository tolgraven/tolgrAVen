(ns website.components.ui.liquid-glass.optics
  "Pure lens profiles and distance fields. Dimensions are measured CSS-pixel
   coordinates at the browser boundary, never authored pixel styling.")

(defn clamp [low high value] (max low (min high value)))
(defn finite? [value] (and (number? value) (js/Number.isFinite value)))

(defn surface-height [profile t]
  (let [t' (clamp 0 1 t)
        convex (js/Math.sqrt (max 0 (- 1 (* (- 1 t') (- 1 t')))))
        concave (- 1 convex)]
    (case profile
      :convex convex
      :squircle (js/Math.pow (max 0 (- 1 (js/Math.pow (- 1 t') 4))) 0.25)
      :concave concave
      :lip (let [mix (* t' t' t' (+ (* t' (- (* 6 t') 15)) 10))]
             (+ (* convex (- 1 mix)) (* concave mix)))
      (profile t'))))

(defn displacement [profile index thickness t]
  (let [step 0.001
        low (max 0 (- t step))
        high (min 1 (+ t step))
        height (surface-height profile t)
        slope (/ (- (surface-height profile high) (surface-height profile low))
                 (max step (- high low)))
        normal-z (/ 1 (js/Math.sqrt (+ 1 (* slope slope))))
        normal-x (* (- slope) normal-z)
        ratio (/ 1 index)
        transmitted (js/Math.sqrt (max 0 (- 1 (* ratio ratio (- 1 (* normal-z normal-z))))))
        factor (- (* ratio normal-z) transmitted)
        ray-x (* factor normal-x)
        ray-z (+ (- ratio) (* factor normal-z))
        result (/ (* thickness height ray-x) (max 0.000001 (- ray-z)))]
    (when-not (and (finite? height) (finite? result))
      (throw (ex-info "Glass surface must return finite heights" {})))
    (if (or (= index 1) (zero? thickness)) 0 result)))

(defn profile-table [profile index thickness]
  (let [values (mapv #(displacement profile index thickness (/ % 256)) (range 257))]
    {:values values
     :maximum (reduce max 0 (map abs values))}))

(defn sample [{:keys [values]} t]
  (let [position (* 256 (clamp 0 1 t))
        index (min 255 (int position))
        fraction (- position index)]
    (+ (* (nth values index) (- 1 fraction)) (* (nth values (inc index)) fraction))))

(defn rounded-distance [width height radius x y]
  (let [px (- x (/ width 2))
        py (- y (/ height 2))
        radius' (clamp 0 (/ (min width height) 2) radius)
        qx (- (abs px) (- (/ width 2) radius'))
        qy (- (abs py) (- (/ height 2) radius'))
        ox (max 0 qx)
        oy (max 0 qy)
        length (js/Math.hypot ox oy)
        sx (if (neg? px) -1 1)
        sy (if (neg? py) -1 1)
        distance (- radius' (+ length (min (max qx qy) 0)))]
    [distance
     (if (pos? length) (- (* sx (/ ox length))) (if (> qx qy) (- sx) 0))
     (if (pos? length) (- (* sy (/ oy length))) (if (> qx qy) 0 (- sy)))]))

(defn ellipse-distance [width height x y]
  ;; Gradient-normalized ellipse distance is accurate near the bezel, where the
  ;; optical field is evaluated; the flat interior never needs an exact SDF.
  (let [rx (/ width 2)
        ry (/ height 2)
        px (- x rx)
        py (- y ry)
        radial (js/Math.hypot (/ px rx) (/ py ry))]
    (if (zero? radial)
      [(min rx ry) 0 0]
      (let [gx (/ px (* rx rx radial))
            gy (/ py (* ry ry radial))
            length (js/Math.hypot gx gy)]
        [(/ (- 1 radial) length) (- (/ gx length)) (- (/ gy length))]))))

(defn- polygon-edges [points]
  ;; This native numeric buffer belongs only to raster generation, beside the
  ;; ImageData buffers. Profiling a 32-point lens exposed repeated persistent-
  ;; vector destructuring and edge arithmetic in every pixel as the hot path.
  ;; Specs/points remain Clojure data; prepare these invariant edges once per map.
  (let [edges (js/Float64Array. (* 7 (count points)))]
    (doseq [index (range (count points))]
      (let [[ax ay] (nth points index)
            [bx by] (nth points (mod (inc index) (count points)))
            dx (- bx ax)
            dy (- by ay)
            offset (* 7 index)]
        (doseq [[slot value] (map-indexed vector [ax ay bx by dx dy (+ (* dx dx) (* dy dy))])]
          (aset edges (+ offset slot) value))))
    edges))

(defn- edge-distance [edges x y]
  (loop [offset 0, inside? false, nearest-distance js/Infinity, nx 0, ny 0]
    (if (= offset (.-length edges))
      (let [length (js/Math.sqrt nearest-distance)
            sign (if inside? 1 -1)]
        [(* sign length) (* sign nx) (* sign ny)])
      (let [ax (aget edges offset)
            ay (aget edges (+ offset 1))
            bx (aget edges (+ offset 2))
            by (aget edges (+ offset 3))
            dx (aget edges (+ offset 4))
            dy (aget edges (+ offset 5))
            denominator (aget edges (+ offset 6))
            fraction (if (pos? denominator)
                       (clamp 0 1 (/ (+ (* (- x ax) dx) (* (- y ay) dy)) denominator)) 0)
            vx (- x (+ ax (* fraction dx)))
            vy (- y (+ ay (* fraction dy)))
            squared (+ (* vx vx) (* vy vy))
            nearer? (< squared nearest-distance)
            length (if nearer? (max 0.000001 (js/Math.sqrt squared)) 1)
            crosses? (and (not= (> ay y) (> by y))
                          (< x (+ ax (/ (* (- y ay) dx) (- by ay)))))]
        (recur (+ offset 7) (if crosses? (not inside?) inside?)
               (if nearer? squared nearest-distance)
               (if nearer? (/ vx length) nx) (if nearer? (/ vy length) ny))))))

(defn polygon-distance [points x y]
  (edge-distance (polygon-edges points) x y))

(defn raster-size [width height resolution max-pixels max-side]
  (when-not (and (finite? width) (finite? height) (pos? width) (pos? height))
    (throw (ex-info "Glass dimensions must be positive and finite" {})))
  (let [ratio (min resolution (/ max-side width) (/ max-side height)
                   (js/Math.sqrt (/ max-pixels (* width height))))
        w (max 1 (min max-pixels (int (* width ratio))))
        h (max 1 (min (int (/ max-pixels w)) (int (* height ratio))))]
    [w h]))

(defn field
  "Generate bounded RG displacement and RGBA rim-light buffers. Mutation is
   confined to the native ImageData boundary; geometry/profile inputs are data."
  [{:keys [width height radius bezel thickness shape points profile index
           resolution max-pixels max-side light-angle light-focus]}]
  (let [[w h] (raster-size width height resolution max-pixels max-side)
        table (profile-table profile index thickness)
        maximum (:maximum table)
        displacement-data (js/Uint8ClampedArray. (* w h 4))
        highlight-data (js/Uint8ClampedArray. (* w h 4))
        polygon (mapv (fn [[x y]] [(* x width) (* y height)]) points)
        edges (when (= shape :polygon) (polygon-edges polygon))
        distance! (case shape
                    :ellipse #(ellipse-distance width height %1 %2)
                    :polygon #(edge-distance edges %1 %2)
                    #(rounded-distance width height (if (= shape :capsule) (/ (min width height) 2) radius) %1 %2))
        angle (* light-angle (/ js/Math.PI 180))
        light-x (js/Math.cos angle)
        light-y (js/Math.sin angle)
        bezel' (max 0.000001 (min bezel (/ (min width height) 2)))]
    (dotimes [y h]
      (dotimes [x w]
        (let [offset (* 4 (+ x (* y w)))
              [distance nx ny] (distance! (* (+ x 0.5) (/ width w)) (* (+ y 0.5) (/ height h)))
              edge? (and (>= distance 0) (< distance bezel'))
              t (/ distance bezel')
              amount (if (and edge? (pos? maximum)) (/ (sample table t) maximum) 0)
              rim (if edge?
                    (* (js/Math.pow (- 1 t) light-focus)
                       (max 0 (+ (* (- nx) light-x) (* (- ny) light-y)))) 0)]
          (aset displacement-data offset (+ 128 (* 127 nx amount)))
          (aset displacement-data (inc offset) (+ 128 (* 127 ny amount)))
          (aset displacement-data (+ offset 2) 128)
          (aset displacement-data (+ offset 3) 255)
          (aset highlight-data offset 255)
          (aset highlight-data (inc offset) 255)
          (aset highlight-data (+ offset 2) 255)
          (aset highlight-data (+ offset 3) (* 255 rim)))))
    {:width w
     :height h
     :maximum maximum
     :displacement displacement-data
     :highlight highlight-data}))
