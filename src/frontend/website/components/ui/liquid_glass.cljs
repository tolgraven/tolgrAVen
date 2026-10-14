(ns website.components.ui.liquid-glass
  "A refractive background for ordinary Hiccup. Children retain their own
   opacity, focus, state and DOM; only the independently sized glass is filtered."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.component.registry]
            [website.component]
            [website.component.visibility :as visibility]
            [website.listener :as listener]
            [website.schema.declarations :as declarations]
            [website.components.ui.liquid-glass.optics :as optics]
            [website.components.ui.liquid-glass.maps :as maps]))

(def relative-length
  [:re "^(?:0|(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:rem|em|vw|vh|vmin|vmax))$"])
(def outset-length
  [:re "^(?:0|(?:-?[0-9]+(?:\\.[0-9]+)?|-?\\.[0-9]+)(?:rem|em|vw|vh|vmin|vmax|%))$"])
(def unit-interval [:and [:fn optics/finite?] [:>= 0] [:<= 1]])
(def spec-schema
  [:merge declarations/component-spec
   [:map
    [:?enabled? :boolean]
    [:?mode [:enum :auto :refraction :frosted]]
    [:?shape [:enum :rounded-rect :capsule :ellipse :polygon]]
    [:?points [:vector {:min 3, :max 32} [:tuple unit-interval unit-interval]]]
    [:?radius relative-length]
    [:?bezel-width relative-length]
    [:?thickness relative-length]
    [:?profile [:or [:enum :convex :squircle :concave :lip] fn?]]
    [:?index [:and [:fn optics/finite?] [:>= 1] [:<= 3]]]
    [:?scale [:and [:fn optics/finite?] [:>= 0] [:<= 4]]]
    [:?opacity unit-interval]
    [:?content-opacity unit-interval]
    [:?blur relative-length]
    [:?saturation [:and [:fn optics/finite?] [:>= 0] [:<= 4]]]
    [:?brightness [:and [:fn optics/finite?] [:>= 0] [:<= 4]]]
    [:?light-angle [:and [:fn optics/finite?] [:>= -180] [:<= 180]]]
    [:?light-focus [:and [:fn optics/finite?] [:>= 0.1] [:<= 8]]]
    [:?light-opacity unit-interval]
    [:?tint :string]
    [:?border :string]
    [:?shadow :string]
    [:?outset [:or outset-length [:tuple outset-length outset-length outset-length outset-length]]]
    [:?content-props :map]
    [:?resolution [:and [:fn optics/finite?] [:>= 0.1] [:<= 2]]]
    [:?max-pixels [:int {:min 256, :max 1048576}]]
    [:?max-side [:int {:min 16, :max 2048}]]
    [:?cache? :boolean]
    [:?on-error fn?]]])

(def defaults
  {:enabled? true
   :mode :auto
   :shape :rounded-rect
   :points [[0.5 0] [1 0.4] [0.8 1] [0.2 1] [0 0.4]]
   :radius "1.5rem"
   :bezel-width "0.75rem"
   :thickness "2.5rem"
   :profile :squircle
   :index 1.5
   :scale 1
   :opacity 0.85
   :content-opacity 1
   :blur "0.04rem"
   :saturation 1.2
   :brightness 1.05
   :light-angle -60
   :light-focus 2
   :light-opacity 0.35
   :tint "rgb(255 255 255 / 0.08)"
   :border "0.0625rem solid rgb(255 255 255 / 0.3)"
   :shadow "0 0.75rem 2rem rgb(0 0 0 / 0.18)"
   :outset "0"
   :resolution 0.5
   :max-pixels 65536
   :max-side 1024
   :cache? true})

(defn settings [spec]
  ;; Internal validation can be disabled in production. Hard bounds still guard
  ;; native allocations and filter cost for data-driven callers.
  (reduce (fn [value [key low high]]
            (let [number (get value key)]
              (assoc value key (optics/clamp low high
                                (if (optics/finite? number) number (get defaults key))))))
          (assoc (merge defaults spec) :points
                 (let [points (vec (take 32 (:points spec (:points defaults))))]
                   (if (and (>= (count points) 3)
                            (every? (fn [point] (and (= 2 (count point)) (every? optics/finite? point))) points))
                     (mapv (fn [[x y]] [(optics/clamp 0 1 x) (optics/clamp 0 1 y)]) points)
                     (:points defaults))))
          [[:index 1 3] [:scale 0 4] [:opacity 0 1] [:content-opacity 0 1]
           [:saturation 0 4] [:brightness 0 4] [:light-angle -180 180]
           [:light-focus 0.1 8] [:light-opacity 0 1] [:resolution 0.1 2]
           [:max-pixels 256 1048576] [:max-side 16 2048]]))

(defn supported? [mode]
  (and (exists? js/window) (exists? js/CSS)
       (not= mode :frosted)
       (.supports js/CSS "backdrop-filter" "url(#glass)")
       (or (= mode :refraction)
           ;; Syntax acceptance does not establish SVG backdrop rendering.
           ;; Auto opts into the demonstrated Chromium implementation; callers
           ;; can explicitly opt in as other engines gain actual support.
           (boolean (re-find #"(?:Chrome|Chromium|Edg)/" (.-userAgent js/navigator))))))

(defn- relative-value [value element width height]
  (let [[_ number unit] (re-matches #"([0-9]*\.?[0-9]+)(rem|em|vw|vh|vmin|vmax)?" value)
        multiplier (case unit
                     "rem" (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))
                     "em" (js/parseFloat (.-fontSize (js/getComputedStyle element)))
                     "vw" (/ (.-innerWidth js/window) 100)
                     "vh" (/ (.-innerHeight js/window) 100)
                     "vmin" (/ (min (.-innerWidth js/window) (.-innerHeight js/window)) 100)
                     "vmax" (/ (max (.-innerWidth js/window) (.-innerHeight js/window)) 100)
                     1)
        result (* (js/parseFloat number) multiplier)]
    (if (optics/finite? result) (optics/clamp 0 (* 4 (max width height)) result) 0)))

(defn- geometry [element options]
  ;; offset dimensions are layout dimensions: ancestor entrance transforms must
  ;; not bake their temporary scale into a map that persists after animation.
  (let [width (.-offsetWidth element)
        height (.-offsetHeight element)]
    (when (and (pos? width) (pos? height))
      {:width width
       :height height
       :radius (relative-value (:radius options) element width height)
       :bezel (relative-value (:bezel-width options) element width height)
       :thickness (relative-value (:thickness options) element width height)
       :blur (relative-value (:blur options) element width height)})))

(defn- use-measurement [*surface *unit active? options]
  (let [[size set-size!] (r/use-state nil)]
    (r/use-effect
      (fn []
        (when (and active? (.-current *surface))
          (let [element (.-current *surface)
                *frame (atom nil)
                measure! #(set-size! (geometry element options))
                queue! #(when-not @*frame
                          (reset! *frame
                                  (js/requestAnimationFrame
                                   (fn [] (reset! *frame nil) (measure!)))))
                observer (when (exists? js/ResizeObserver) (js/ResizeObserver. queue!))
                stop! (listener/listen! :liquid-glass [{:target js/window
                                                      :event "resize"
                                                      :handler queue!
                                                      :passive? true}])]
            (when observer
              (.observe observer element)
              ;; A relative-unit probe also invalidates optical lengths when a
              ;; font changes without changing the surface's fixed dimensions.
              (when (.-current *unit) (.observe observer (.-current *unit))))
            (measure!)
            (fn []
              (when observer (.disconnect observer))
              (when @*frame (js/cancelAnimationFrame @*frame))
              (stop!)))))
      [active? (:radius options) (:bezel-width options) (:thickness options) (:blur options)])
    size))

(defn- negative-length [value]
  (if (= value "0") "0" (str "calc(-1 * " value ")")))

(defn- clip-style [{:keys [shape radius points]}]
  (case shape
    :ellipse {:border-radius "50%"}
    :capsule {:border-radius "999em"}
    :polygon {:clip-path (str "polygon(evenodd, "
                             (string/join ", " (map (fn [[x y]] (str (* 100 x) "% " (* 100 y) "%")) (take 32 points))) ")")}
    {:border-radius radius}))

(defn- filter-form [id size images options]
  (let [{:keys [width height blur]} size
        image-props {:x 0, :y 0, :width width, :height height, :preserveAspectRatio "none"}]
    [:svg.liquid-glass__filters {:width 0, :height 0, :aria-hidden true, :focusable false}
     [:defs
      [:filter {:id id
                :x 0
                :y 0
                :width width
                :height height
                :filterUnits "userSpaceOnUse"
                :colorInterpolationFilters "sRGB"}
       [:feGaussianBlur {:in "SourceGraphic", :stdDeviation blur, :result "blurred"}]
       [:feColorMatrix {:in "blurred", :type "saturate", :values (:saturation options), :result "saturated"}]
       [:feComponentTransfer {:in "saturated", :result "background"}
        [:feFuncR {:type "linear", :slope (:brightness options)}]
        [:feFuncG {:type "linear", :slope (:brightness options)}]
        [:feFuncB {:type "linear", :slope (:brightness options)}]]
       [:feImage (assoc image-props :href (:displacement images) :result "encoded")]
       ;; SVG's actual equation is scale * (channel - .5), so scale is twice
       ;; maximum displacement. Correct the 8-bit neutral 128 to exactly .5.
       [:feComponentTransfer {:in "encoded", :result "vectors"}
        [:feFuncR {:type "linear", :slope (/ 255 254), :intercept (- (/ 1 254))}]
        [:feFuncG {:type "linear", :slope (/ 255 254), :intercept (- (/ 1 254))}]]
       [:feDisplacementMap {:in "background"
                           :in2 "vectors"
                           :scale (* 2 (:maximum images) (:scale options))
                           :xChannelSelector "R"
                           :yChannelSelector "G"
                           :result "refracted"}]
       [:feImage (assoc image-props :href (:highlight images) :result "rim")]
       [:feComponentTransfer {:in "rim", :result "highlight"}
        [:feFuncA {:type "linear", :slope (:light-opacity options)}]]
       [:feBlend {:in "refracted", :in2 "highlight", :mode "screen"}]]]]))

(defc <liquid-glass>
  "Wrap spec & forms with a configurable, independently sized glass background.
   Opacity applies to the glass, not its children. See doc/liquid-glass.md."
  {:features [:props]
   :spec-schema spec-schema}
  [spec & forms]
  (let [options (settings spec)
        id (str "liquid-glass-" (string/replace (r/use-id) #"[^a-zA-Z0-9_-]" "-"))
        *surface (r/use-ref nil)
        *unit (r/use-ref nil)
        *on-error (r/use-ref nil)
        [in-view? set-in-view!] (r/use-state false)
        [document-visible? set-document-visible!] (r/use-state true)
        [support? set-support!] (r/use-state false)
        [images set-images!] (r/use-state nil)
        [failed? set-failed!] (r/use-state false)
        active? (and (:enabled? options) support? in-view? document-visible?
                     (pos? (:opacity options)) (pos? (:scale options)))
        size (use-measurement *surface *unit active? options)
        key (when size (merge (dissoc size :blur)
                             (select-keys options [:shape :points :profile :index :resolution
                                                   :max-pixels :max-side :light-angle :light-focus])))
        refractive? (and active? (some? images) (= key (:key images)) (not failed?))
        inset (if (vector? (:outset options))
                (string/join " " (map negative-length (:outset options)))
                (negative-length (:outset options)))
        fallback (str "blur(" (:blur options) ") saturate(" (:saturation options)
                      ") brightness(" (:brightness options) ")")]
    ;; Native hooks are necessary only for element geometry, browser support and
    ;; cancellable image generation. React owns every rendered node and style.
    (r/use-effect (fn [] (set! (.-current *on-error) (:on-error options)) nil) [(:on-error options)])
    (r/use-effect #(set-support! (boolean (supported? (:mode options)))) [(:mode options)])
    (r/use-effect
      (fn []
        (when (and (exists? js/window) (.-current *surface) (:enabled? options) support?)
          (let [owner (visibility/setup {:once? false
                                        :threshold 0
                                        :on-change set-in-view!})
                update! #(set-document-visible! (not (.-hidden js/document)))
                stop! (listener/listen! :liquid-glass [{:target js/document
                                                      :event "visibilitychange"
                                                      :handler update!}])]
            ((:mount! owner) (.-current *surface))
            (update!)
            (fn [] ((:stop! owner)) (stop!)))))
      [(:enabled? options) support?])
    (r/use-effect
      (fn []
        (when (and active? key)
          (let [*current? (atom true)
                stop! (maps/schedule!
                       (fn []
                         (when @*current?
                           (try
                             (let [result (maps/acquire! key (:cache? options))]
                               (set-failed! false)
                               (set-images! (assoc result :key key)))
                             (catch :default error
                               (set-failed! true)
                               (when-let [callback (.-current *on-error)] (callback error)))))))]
            (fn [] (reset! *current? false) (stop!)))))
      [active? key (:cache? options)])
    [:div.liquid-glass {:data-glass-mode (if refractive? "refraction" "frosted")
                       :data-glass-error (when failed? true)}
     (when refractive? (filter-form id size images options))
     [:div.liquid-glass__surface
      {:ref *surface
       :aria-hidden true
       :style (merge (clip-style options)
                     {:inset inset
                      :opacity (if (:enabled? options) (:opacity options) 0)
                      :background (:tint options)
                      :border (:border options)
                      :box-shadow (:shadow options)
                      :backdrop-filter (cond
                                         (not (:enabled? options)) "none"
                                         refractive? (str "url(#" id ")")
                                         :else fallback)
                      :-webkit-backdrop-filter (if (:enabled? options) fallback "none")})}]
     [:span.liquid-glass__unit {:ref *unit, :aria-hidden true}]
     (into [:div.liquid-glass__content
            (r/merge-props {:style {:opacity (:content-opacity options)}} (:content-props options))]
           forms)]))
