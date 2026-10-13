(ns website.component-font-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.component]
            [website.subs]

            [website.modules.styled-input.views :as styled-input]
            [website.component.font :as font]
            [website.component.motion :as motion]
            [website.component.restore :as restore]

            [website.test-support :as support]
            [website.macros :refer-macros [defc]]))

(defc <sample>
  {:features [[:font (fn [options & _] (when options (into {} options)))]]}
  [options attrs]
  [:pre (update attrs :style merge {:font-family "var(--monospace-font)", :font-size "1rem"}) [:code {:style {:font-family "inherit"}} [:span "MWiw0123456789"]]])

(defn- fixture! []
  (go-promise
    (let [element (.createElement js/document "div")
          root (await! (support/create-root! element))]
      (.appendChild (.-body js/document) element)
      {:element element
       :root root
       :close! #(do (support/unmount! root) (.remove element))})))
(defn- phase [node] (.getAttribute node "data-font-phase"))
(defn- until! [node value] (support/wait-for! #(= value (phase node))))
(defn- tick! [ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))
(defn- ended! [node name]
  (.dispatchEvent node (js/AnimationEvent. "animationend" #js {:bubbles true, :animationName name})))
(defn- options []
  (let [name (str "font-test-" (random-uuid))]
    (assoc font/fira :family name :initial-family (str name "-initial") :fade-ms 200)))
(defn- capture-loads! []
  (let [original (r/get-handler :fx :font/load)
        *loads (atom [])]
    (r/reg-fx :font/load #(swap! *loads conj %))
    {:loads *loads
     :original original
     :restore! #(r/reg-fx :font/load original)}))

(deftest actual-font-load-crossfades-in-place-and-removes-original-text
  (async done
    (-> (go-promise
          (let [{:keys [element root close!]} (await! (fixture!))
                {:keys [loads original restore!]} (capture-loads!)
                opts (options)
                face (js/FontFace. (:family opts) "url('/webfonts/FiraCode-Retina.woff2')")
                sheet (.createElement js/document "link")
                monospace (.createElement js/document "link")
                *ended (atom 0)
                *adjusted? (atom false)]
            (.add (.-fonts js/document) face)
            (set! (.-rel sheet) "stylesheet")
            (set! (.-href sheet) "/css/tolgraven/main.min.css")
            (.appendChild (.-head js/document) sheet)
            (set! (.-rel monospace) "stylesheet")
            (set! (.-href monospace) "/css/tolgraven/modules/monospace.min.css")
            (try
              (await! (js/Promise. (fn [resolve reject]
                                    (set! (.-onload monospace) resolve)
                                    (set! (.-onerror monospace) reject)
                                    (.appendChild (.-head js/document) monospace))))
              (reset! *adjusted?
                      (await! (-> (.load (.-fonts js/document) "1em 'Fira Code Fallback'")
                                  (.then #(pos? (.-length %)))
                                  (.catch (constantly false)))))
              (r/with-framework-bindings [motion/reduced-motion? (constantly false)]
                (await! (support/render! root
                          [:section [<sample> opts {:class [:caller "other"]
                                                    :style {:color "rgb(10, 20, 30)"}
                                                    :on-animation-end #(swap! *ended inc)}]
                                    [<sample> opts {}]]))
                (let [nodes (array-seq (.querySelectorAll element "pre"))
                      node (first nodes)
                      code (.querySelector node "code")
                      outgoing (.querySelector node ".font-text-current")
                      glyphs (.querySelector code "span")
                      original-width (.-width (.getBoundingClientRect glyphs))
                      original-height (.-height (.getBoundingClientRect glyphs))]
                  (await! (until! node "fallback"))
                  (is (= 1 (count @loads)) "Mounted consumers share one FontFaceSet request")
                  (is (= 1 (.-length (.querySelectorAll node ".font-text-stack > span"))))
                  (is (= "MWiw0123456789" (.-textContent node)))
                  (is (= "1" (.-opacity (js/getComputedStyle node))) "Container never fades")
                  (original (first @loads))
                  (await! (until! node "crossfade"))
                  (let [incoming (.querySelector node ".font-text-next")
                        incoming-code (.querySelector incoming "code")
                        animations (mapv #(.getAnimations %) [outgoing incoming])
                        outgoing-animation (aget (first animations) 0)
                        incoming-animation (aget (second animations) 0)]
                    (is (= "loaded" (.-status face)))
                    (is (= "true" (.getAttribute incoming "aria-hidden")))
                    (is (.hasAttribute incoming "inert"))
                    (is (= "none" (.-pointerEvents (js/getComputedStyle incoming))))
                    (is (= "none" (.-userSelect (js/getComputedStyle incoming))))
                    (is (identical? code (.querySelector outgoing "code")))
                    (is (.includes (.-fontFamily (js/getComputedStyle incoming-code)) (:family opts)))
                    (is (.includes (.-fontFamily (js/getComputedStyle code)) (:initial-family opts)))
                    (is (= (.-startTime outgoing-animation) (.-startTime incoming-animation))
                        "Both fades start in the same CSS animation frame")
                    (is (= 200 (.-duration (.getTiming (.-effect incoming-animation)))))
                    (is (= "linear" (.-easing (.getTiming (.-effect incoming-animation)))))
                    (await! (tick! 75))
                    (let [old-opacity (js/parseFloat (.-opacity (js/getComputedStyle outgoing)))
                          new-opacity (js/parseFloat (.-opacity (js/getComputedStyle incoming)))]
                      (is (< 0 old-opacity 1))
                      (is (< 0 new-opacity 1))
                      (is (< (js/Math.abs (- 1 (+ old-opacity new-opacity))) 0.05)
                          "There is no transparent interval between the fonts")
                      (is (= "1" (.-opacity (js/getComputedStyle node)))))
                    (ended! incoming-code "font-fade-in")
                    (await! (support/settle!))
                    (is (= "crossfade" (phase node)) "Child animation cannot finish the fade")
                    (ended! incoming "font-fade-in")
                    (await! (until! node "ready"))
                    (is (not (.contains node outgoing)) "Old text is removed after fading out")
                    (is (identical? incoming-code (.querySelector node "code")))
                    (is (= 1 (.-length (.querySelectorAll node ".font-text-stack > span"))))
                    (is (not (.hasAttribute incoming "aria-hidden")))
                    (is (not (.hasAttribute incoming "inert")))
                    (is (= "MWiw0123456789" (.-textContent node)))
                    (when @*adjusted?
                      (let [loaded (.querySelector incoming-code "span")]
                        (is (< (js/Math.abs (- original-width (.-width (.getBoundingClientRect loaded)))) 0.2))
                        (is (< (js/Math.abs (- original-height (.-height (.getBoundingClientRect loaded)))) 0.5))))
                    (is (.contains (.-classList node) "caller"))
                    (is (.contains (.-classList node) "other"))
                    (is (= "rgb(10, 20, 30)" (.-color (.-style node))))
                    (is (pos? @*ended) "The root's existing animation handler remains active")
                    (await! (until! (second nodes) "ready"))
                    (is (= 1 (count @loads))))))
              (finally (close!) (restore!)
                       (.delete (.-fonts js/document) face) (.remove sheet) (.remove monospace)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest failure-and-stale-completion-keep-fallback-readable
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                {:keys [loads restore!]} (capture-loads!)
                opts (options)
                face (js/FontFace. (:family opts) "url('/webfonts/FiraCode-Retina.woff2')")]
            (.add (.-fonts js/document) face)
            (try
              (await! (support/render! root [<sample> opts {}]))
              (let [node (.querySelector element "pre")]
                (await! (until! node "fallback"))
                (let [[options token] (first @loads)]
                  (r/dispatch [:font/loaded (font/request options) token false])
                  (await! (until! node "failed"))
                  (r/dispatch [:font/loaded (font/request options) token true])
                  (await! (support/settle!))
                  (is (= "failed" (phase node)) "A late success cannot replay an expired attempt")
                  (is (= "1" (.-opacity (js/getComputedStyle node))))
                  (is (= "MWiw0123456789" (.-textContent node)))
                  (await! (support/render! root nil))
                  (await! (support/settle!))
                  (is (not-any? #(= opts (:options %))
                                (vals (get (await! (support/state-at! [:fonts])) :instances)))
                      "Unmount removes transient ownership")))
              (finally (close!) (restore!) (.delete (.-fonts js/document) face)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest native-ready-and-disabled-owners-never-request-or-animate
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                {:keys [loads restore!]} (capture-loads!)
                opts (options)
                original (js/FontFace. (:initial-family opts) "url('/webfonts/FiraCode-Retina.woff2')")]
            (.add (.-fonts js/document) original)
            (try
              (await! (.load original))
              (await! (support/render! root [<sample> opts {}]))
              (let [node (.querySelector element "pre")]
                (await! (until! node "native"))
                (is (empty? @loads))
                (is (nil? (.querySelector node ".font-text-next")))
                (await! (support/render! root [<sample> nil {}]))
                (is (nil? (phase (.querySelector element "pre"))))
                (is (empty? @loads)))
              (finally (close!) (restore!) (.delete (.-fonts js/document) original)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest reduced-motion-upgrades-directly-and-unmounted-owner-is-not-revived
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                {:keys [loads restore!]} (capture-loads!)
                opts (options)
                face (js/FontFace. (:family opts) "url('/webfonts/FiraCode-Retina.woff2')")]
            (.add (.-fonts js/document) face)
            (try
              (r/with-framework-bindings [motion/reduced-motion? (constantly true)]
                (await! (support/render! root [<sample> opts {}]))
                (let [node (.querySelector element "pre")]
                  (await! (until! node "fallback"))
                  (let [[options token] (first @loads)]
                    (r/dispatch [:font/loaded (font/request options) token true])
                    (await! (until! node "ready"))
                    (is (nil? (.querySelector node ".font-text-next")))
                    (await! (support/render! root nil))
                    (r/with-framework-bindings [motion/reduced-motion? (constantly false)
                                  restore/local-document? (constantly true)]
                      (await! (support/render! root [<sample> opts {}]))
                      (let [returned (.querySelector element "pre")]
                        (await! (until! returned "ready"))
                        (is (nil? (.querySelector returned ".font-text-next"))
                            "A local return skips the fade even without reduced motion"))
                      (await! (support/render! root nil)))
                    (r/dispatch [:font/reveal (random-uuid)])
                    (r/dispatch [:font/loaded (font/request options) token true])
                    (await! (support/settle!))
                    (is (not-any? #(= opts (:options %))
                                  (vals (get (await! (support/state-at! [:fonts])) :instances)))))))
              (finally (close!) (restore!) (.delete (.-fonts js/document) face)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest search-text-crossfades-with-one-live-input-and-one-caret
  (async done
    (-> (go-promise
          (let [{:keys [element root close!]} (await! (fixture!))
                {:keys [loads original restore!]} (capture-loads!)
                opts (options)
                model (r/atom "re-frame")
                face (js/FontFace. (:family opts) "url('/webfonts/FiraCode-Retina.woff2')")
                sheet (.createElement js/document "link")]
            (.add (.-fonts js/document) face)
            (set! (.-rel sheet) "stylesheet")
            (set! (.-href sheet) "/css/tolgraven/modules/styled-input.min.css")
            (.appendChild (.-head js/document) sheet)
            (try
              (r/with-framework-bindings [font/fira opts
                            motion/reduced-motion? (constantly false)]
                (await! (support/render! root
                          [styled-input/<input-text-styled> :model model :id "font-input" :open? true]))
                (await! (support/wait-for! #(= 8 (.-length (.querySelectorAll element "[data-font-phase=fallback]")))))
                (let [input (.querySelector element "#font-input")
                      caret (.querySelector element "#styled-caret")
                      text (.querySelector element "[data-font-phase]")]
                  (.focus input #js {:preventScroll true})
                  (is (= 1 (count @loads)) "All visible letters share one ready-face load")
                  (original (first @loads))
                  (await! (until! text "crossfade"))
                  (is (= 1 (.-length (.querySelectorAll element "input"))))
                  (is (= 1 (.-length (.querySelectorAll element "#styled-caret"))))
                  (is (identical? input (.-activeElement js/document)))
                  (await! (until! text "ready"))
                  (is (identical? input (.querySelector element "#font-input")))
                  (is (identical? caret (.querySelector element "#styled-caret")))
                  (is (nil? (.querySelector element ".font-text-next")))
                  (is (= "re-frame" (.-value input)))))
              (finally (close!) (restore!) (.delete (.-fonts js/document) face) (.remove sheet)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
