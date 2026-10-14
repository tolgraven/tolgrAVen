(ns website.liquid-glass-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.react.server :as server]
            [website.test-support :as support]
            [website.components.ui.liquid-glass :as glass]
            [website.components.ui.liquid-glass.maps :as maps]
            [website.liquid-glass-optics-test :as fixtures]
            [website.listener :as listener]))

(defn with-root [f]
  (let [element (.createElement js/document "div")]
    (set! (.-cssText (.-style element)) "position:fixed;top:1rem;left:1rem;width:20rem;z-index:99999")
    (.appendChild (.-body js/document) element)
    (-> (support/create-root! element)
        (.then (fn [root] (-> (js/Promise.resolve (f root element))
                             (.finally #(support/unmount! root)))))
        (.finally #(.remove element)))))

(defn options [& [extra]]
  (merge {:mode :refraction
          :props {:style {:width "12rem", :height "6rem"}}
          :outset "0"
          :cache? false} extra))

(deftest appearance-changes-retain-content-focus-and-do-not-rebuild-maps
  (async done
    (let [*samples (atom 0)
          profile (fn [t] (swap! *samples inc) t)]
      (-> (with-root
            (fn [root element]
              (go-promise
                (let [spec (options {:profile profile})
                      content [:input {:default-value "Retained"}]]
                  (await! (support/render! root [glass/<liquid-glass> spec content]))
                  (await! (support/wait-for! #(.querySelector element "filter")))
                  (let [input (.querySelector element "input")
                        image (.getAttribute (.querySelector element "feImage") "href")
                        samples @*samples]
                    (.focus input)
                    (await! (support/render! root [glass/<liquid-glass> (assoc spec :opacity 0.3 :scale 2 :light-opacity 0.8 :blur "0.2rem") content]))
                    (is (identical? input (.querySelector element "input")))
                    (is (identical? input (.-activeElement js/document)))
                    (is (= "Retained" (.-value input)))
                    (is (= samples @*samples) "Appearance-only updates must not resample the lens")
                    (is (= image (.getAttribute (.querySelector element "feImage") "href")))
                    (is (= "1" (.-opacity (js/getComputedStyle (.querySelector element ".liquid-glass__content")))))
                    (is (= "0.3" (.-opacity (js/getComputedStyle (.querySelector element ".liquid-glass__surface"))))))))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest expanded-background-and-disabled-mode-preserve-children
  (async done
    (-> (with-root
          (fn [root element]
            (go-promise
              (await! (support/render! root [glass/<liquid-glass> (options {:outset "1rem"}) [:button "Click"]]))
              (await! (support/wait-for! #(.querySelector element "filter")))
              (let [wrapper (.querySelector element ".liquid-glass")
                    surface (.querySelector element ".liquid-glass__surface")
                    button (.querySelector element "button")]
                (is (> (.-offsetWidth surface) (.-offsetWidth wrapper)))
                (is (> (.-offsetHeight surface) (.-offsetHeight wrapper)))
                (is (= "none" (.-pointerEvents (js/getComputedStyle surface))))
                (await! (support/render! root [glass/<liquid-glass> (options {:enabled? false}) [:button "Click"]]))
                (is (identical? button (.querySelector element "button")))
                (is (nil? (.querySelector element "filter")))
                (is (= "0" (.-opacity (js/getComputedStyle surface))))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest multiple-lenses-have-independent-ids-and-reuse-cached-images
  (async done
    (maps/clear-cache!)
    (-> (with-root
          (fn [root element]
            (go-promise
              (await! (support/render! root [:<> [glass/<liquid-glass> (options {:cache? true}) "One"]
                                              [glass/<liquid-glass> (options {:cache? true}) "Two"]]))
              (await! (support/wait-for! #(= 2 (.-length (.querySelectorAll element "filter")))))
              (let [filters (array-seq (.querySelectorAll element "filter"))
                    images (array-seq (.querySelectorAll element "feImage[result=encoded]"))]
                (is (= 2 (count (set (map #(.getAttribute % "id") filters)))))
                (is (apply = (map #(.getAttribute % "href") images)))
                (is (= 1 (count (:order (maps/cache-info)))))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest frosted-and-failed-map-paths-keep-controls-usable
  (async done
    (let [*error (atom nil)]
      (-> (with-root
            (fn [root element]
              (go-promise
                (await! (support/render! root [glass/<liquid-glass> (options {:mode :frosted}) [:button "Fallback"]]))
                (is (nil? (.querySelector element "filter")))
                (is (.includes (.-backdropFilter (js/getComputedStyle (.querySelector element ".liquid-glass__surface"))) "blur"))
                (await! (support/render! root [glass/<liquid-glass> (options {:profile (constantly js/NaN)
                                                                          :on-error #(reset! *error %)}) [:button "Fallback"]]))
                (await! (support/wait-for! #(deref *error)))
                (is (= "Fallback" (.-textContent (.querySelector element "button"))))
                (is (nil? (.querySelector element "filter")))
                (is (= "true" (.getAttribute (.querySelector element ".liquid-glass") "data-glass-error"))))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest unmount-releases-owned-listeners-and-pending-work
  (async done
    (let [before (count @listener/*bindings)
          *scheduled (atom [])
          *cancelled (atom 0)
          original maps/schedule!]
      (set! maps/schedule! (fn [callback] (swap! *scheduled conj callback) #(swap! *cancelled inc)))
      (-> (with-root
            (fn [root element]
              (go-promise
                (await! (support/render! root [glass/<liquid-glass> (options) "Pending"]))
                (await! (support/wait-for! #(seq @*scheduled)))
                (is (> (count @listener/*bindings) before)))))
          (.then (fn []
                   (is (= before (count @listener/*bindings)))
                   (is (pos? @*cancelled))
                   ;; An already dispatched idle callback is also guarded, even
                   ;; if a browser ignores cancellation after removing a panel.
                   (doseq [callback @*scheduled] (callback))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! maps/schedule! original) (done)))))))

(deftest hydration-retains-the-ssr-input-node
  (async done
    (let [element (.createElement js/document "div")
          *errors (atom [])
          form [glass/<liquid-glass> (options) [:input {:default-value "SSR"}]]]
      (.appendChild (.-body js/document) element)
      ;; Manual DOM setup is confined to the hydration fixture.
      (set! (.-cssText (.-style element)) "position:fixed;top:1rem;left:1rem;z-index:99999")
      (set! (.-innerHTML element) (server/render-to-string form))
      (let [input (.querySelector element "input")
            root (r/hydrate-root element form {:on-recoverable-error #(swap! *errors conj %)})]
        (-> (support/wait-for! #(.querySelector element "filter"))
            (.then (fn []
                     (is (identical? input (.querySelector element "input")))
                     (is (= "SSR" (.-value input)))
                     (is (empty? @*errors))))
            (.catch #(is false (str %)))
            (.finally (fn [] (r/unmount root) (.remove element) (done))))))))


(deftest resize-and-font-units-invalidate-geometry-without-remounting
  (async done
    (-> (with-root
          (fn [root element]
            (go-promise
              (let [spec (options {:radius "1em", :thickness "2em"})]
                (await! (support/render! root [glass/<liquid-glass> spec [:input {:default-value "Resize"}]]))
                (await! (support/wait-for! #(.querySelector element "filter")))
                (let [input (.querySelector element "input")
                      original (.getAttribute (.querySelector element "feImage") "href")]
                  (await! (support/render! root [glass/<liquid-glass>
                                                (assoc spec :props {:style {:width "16rem", :height "6rem", :font-size "2rem"}})
                                                [:input {:default-value "Resize"}]]))
                  (await! (support/wait-for! #(when-let [image (.querySelector element "feImage")]
                                               (not= original (.getAttribute image "href")))))
                  (is (identical? input (.querySelector element "input")))
                  (is (= (.-offsetWidth (.querySelector element ".liquid-glass__surface"))
                         (js/parseFloat (.getAttribute (.querySelector element "filter") "width")))))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest strict-mode-and-offscreen-surfaces-release-and-resume-work
  (async done
    (let [before (count @listener/*bindings)
          *samples (atom 0)
          profile (fn [t] (swap! *samples inc) t)]
      (-> (with-root
            (fn [root element]
              (go-promise
                (let [spec (options {:profile profile})
                      form (fn [value] [:> r/strict-mode [glass/<liquid-glass> value [:button "Still here"]]])]
                  (await! (support/render! root (form spec)))
                  (await! (support/wait-for! #(.querySelector element "filter")))
                  (let [button (.querySelector element "button")]
                    (await! (support/render! root (form (assoc spec :props {:style {:width "12rem", :height "6rem", :left "-200vw"}}))))
                    (await! (support/wait-for! #(nil? (.querySelector element "filter"))))
                    (let [samples @*samples]
                      (await! (support/render! root (form (assoc spec :index 2 :props {:style {:width "12rem", :height "6rem", :left "-200vw"}}))))
                      (is (= samples @*samples))
                      (await! (support/render! root (form (assoc spec :index 2))))
                      (await! (support/wait-for! #(.querySelector element "filter")))
                      (is (> @*samples samples))
                      (is (identical? button (.querySelector element "button")))))))))
          (.then #(is (= before (count @listener/*bindings))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest image-cache-is-bounded-and-hits-skip-raster-generation
  (maps/clear-cache!)
  (let [key fixtures/base-field
        start (.now js/performance)
        first-map (maps/acquire! key true)
        generated (.now js/performance)
        cached-map (maps/acquire! key true)
        reused (.now js/performance)]
    (is (identical? first-map cached-map))
    (is (= 4000 (:pixels first-map)))
    (js/console.info "GLASS_TIMING" (js/JSON.stringify (clj->js {:generation-ms (- generated start), :cache-hit-ms (- reused generated), :pixels (:pixels first-map)})))
    (doseq [index (range 14)] (maps/acquire! (assoc key :light-angle index) true))
    (is (= maps/cache-capacity (count (:order (maps/cache-info)))))
    (is (<= (:bytes (maps/cache-info)) maps/cache-byte-budget))
    (is (not (some #{key} (:order (maps/cache-info)))))
    (maps/clear-cache!)))


(deftest default-budget-map-generation-and-reuse
  (let [points (mapv (fn [index]
                       (let [angle (* 2 js/Math.PI (/ index 32))]
                         [(+ 0.5 (* 0.5 (js/Math.cos angle)))
                          (+ 0.5 (* 0.5 (js/Math.sin angle)))]))
                     (range 32))]
    (doseq [shape [:rounded-rect :ellipse :polygon]]
      (maps/clear-cache!)
      (let [key (assoc fixtures/base-field :shape shape :points points
                                         :width 800 :height 220 :resolution 0.5)
            samples (mapv (fn [index]
                            (let [start (.now js/performance)
                                  result (maps/acquire! (assoc key :light-angle index) true)]
                              (is (<= (:pixels result) 65536))
                              (- (.now js/performance) start)))
                          (range 3))
            start (.now js/performance)
            result (maps/acquire! (assoc key :light-angle 2) true)
            elapsed (- (.now js/performance) start)]
        (js/console.info "GLASS_BUDGET_TIMING"
                         (js/JSON.stringify (clj->js {:shape shape
                                                     :generation-ms samples
                                                     :cache-hit-ms elapsed
                                                     :pixels (:pixels result)})))))
    (maps/clear-cache!)))
