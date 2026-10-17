(ns website.strava-graph-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.render-context :as context]
            [clojure.string :as string]
            [website.modules.strava.subs]
            [website.events]
            [website.subs]
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

(r/defc ^:private <hydrated-activity-dots> [activities *committed?]
  (r/use-layout-effect (fn [] (reset! *committed? true) nil) [])
  (into [:div.strava-activities]
        (map-indexed (fn [index activity]
                       ^{:key (:id activity)}
                       [views/<activity-dot> activity index (count activities) 200])
                     activities)))

(deftest native-activity-dots-hydrate-the-same-scatter-and-keep-their-growth-timers
  (async done
    (let [restore! (r/make-restore-fn)
          element (.createElement js/document "div")
          scratch (.createElement js/document "div")
          activities [{:id 91001, :kilojoules 1500, :average_watts 175}
                      {:id 91002, :kilojoules 900, :average_watts 135}
                      {:id 91003, :kilojoules 400, :average_watts 100}]
          *committed? (atom false)
          *root (atom nil)
          *warnings (atom [])
          *recoveries (atom [])
          *timers (atom [])
          form [<hydrated-activity-dots> activities *committed?]
          before-error (.-error js/console)
          before-warn (.-warn js/console)
          before-timeout js/setTimeout
          capture! (fn [original args]
                     (let [message (apply str (interpose " " args))]
                       (when (or (string/includes? message "hydrated")
                                 (string/includes? message "Hydration")
                                 (string/includes? message "didn't match"))
                         (swap! *warnings conj message)))
                     (.apply original js/console (to-array args)))
          dots (fn [owner] (vec (array-seq (.querySelectorAll owner ".strava-activity-dot"))))
          widths (fn [owner] (mapv #(.-width (.-style %)) (dots owner)))]
      (.appendChild (.-body js/document) element)
      (set! (.-error js/console) (fn [& args] (capture! before-error args)))
      (set! (.-warn js/console) (fn [& args] (capture! before-warn args)))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            ;; Independently render the actual owner twice. Hydration retaining
            ;; a stale server style alone cannot prove matching client state.
            (set! (.-innerHTML element)
                  (binding [context/*server?* true] (r/render-to-string form)))
            (set! (.-innerHTML scratch)
                  (binding [context/*server?* true] (r/render-to-string form)))
            (let [server-dots (dots element)
                  initial-widths (widths element)]
              (is (= 3 (count server-dots)))
              (is (= initial-widths (widths scratch))
                  "Independent native SSR renders retain the same initial scatter")
              (is (< 1 (count (distinct initial-widths)))
                  "Dots retain varied initial sizes rather than a uniform replacement")
              (is (every? #(<= 0 (js/parseFloat %) 0.2) initial-widths))
              ;; Observe and forward real timers; do not replace their deadlines
              ;; or invoke callbacks manually to make growth appear complete.
              (set! js/setTimeout
                    (fn [callback delay & args]
                      (let [*timer (when (and (number? delay) (<= 750 delay 3249))
                                     (atom {:delay delay, :called? false}))
                            callback' (if *timer
                                        (fn [& callback-args]
                                          (swap! *timer assoc :called? true)
                                          (apply callback callback-args))
                                        callback)]
                        (when *timer (swap! *timers conj *timer))
                        (.apply before-timeout js/window (to-array (into [callback' delay] args))))))
              (reset! *root (r/hydrate-root element form
                             {:on-recoverable-error #(swap! *recoveries conj (.-message %))}))
              (await! (support/wait-for! #(deref *committed?) "Native activity dots did not hydrate"))
              (await! (support/settle!))
              (is (every? true? (map identical? server-dots (dots element)))
                  "Hydration retains all native server dot nodes")
              (is (= initial-widths (widths element)))
              (is (empty? @*warnings) (str "Hydration warnings: " (pr-str @*warnings)))
              (is (empty? @*recoveries))
              (is (= 3 (count @*timers)) "Each native dot owns one growth deadline")
              (is (every? #(<= 750 (:delay @%) 3249) @*timers))
              (await! (js/Promise. (fn [resolve _] (.call before-timeout js/window resolve 3400))))
              (await! (support/settle!))
              (is (every? #(:called? @%) @*timers) "The original native timer callbacks actually ran")
              (is (= ["1.5em" "0.9em" "0.85em"] (widths element))
                  "Growth still reaches the original energy-based sizes and lower bound")
              (is (every? true? (map identical? server-dots (dots element)))
                  "Growth updates the retained dots rather than replacing them")))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (r/unmount @*root))
                      (set! js/setTimeout before-timeout)
                      (set! (.-error js/console) before-error)
                      (set! (.-warn js/console) before-warn)
                      (.remove element)
                      (restore!)
                      (done)))))))
