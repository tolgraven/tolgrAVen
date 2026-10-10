(ns tolgraven.component-font-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [re-frame.registrar :as registrar]
            [tolgraven.component]
            [tolgraven.component.font :as font]
            [tolgraven.component.motion :as motion]
            [tolgraven.component.restore :as restore]
            [tolgraven.react :as rf]
            [tolgraven.test-support :as support]
            [tolgraven.macros :refer-macros [defc]]))

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
  (let [original (registrar/get-handler :fx :font/load)
        *loads (atom [])]
    (rf/reg-fx :font/load #(swap! *loads conj %))
    {:loads *loads
     :original original
     :restore! #(rf/reg-fx :font/load original)}))

(deftest actual-font-load-is-shared-and-changes-glyphs-only-while-hidden
  (async done
    (-> (go-promise
          (let [{:keys [element root close!]} (await! (fixture!))
                {:keys [loads original restore!]} (capture-loads!)
                opts (options)
                face (js/FontFace. (:family opts) "url('/webfonts/FiraCode-Retina.woff2')")
                sheet (.createElement js/document "link")
                monospace (.createElement js/document "link")
                *hidden (atom [])
                observer (js/MutationObserver.
                           (fn [changes]
                             (doseq [change (array-seq changes)
                                     :let [node (.-target change)]
                                     :when (= "swapped" (phase node))]
                               (swap! *hidden conj (.-opacity (js/getComputedStyle node))))))
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
              (with-redefs [motion/reduced-motion? (constantly false)]
                (await! (support/render! root
                          [:section [<sample> opts {:class [:caller "other"]
                                                    :style {:color "rgb(10, 20, 30)"}
                                                    :on-animation-end #(swap! *ended inc)}]
                                    [<sample> opts {}]]))
                (let [nodes (array-seq (.querySelectorAll element "pre"))
                      node (first nodes)
                      code (.querySelector node "code")
                      span (.querySelector node "span")
                      original-width (.-width (.getBoundingClientRect span))
                      original-height (.-height (.getBoundingClientRect span))]
                  (.observe observer node #js {:attributes true, :attributeFilter #js ["data-font-phase"]})
                  (await! (until! node "fallback"))
                  (is (= 1 (count @loads)) "Mounted consumers share one FontFaceSet request")
                  (is (= "MWiw0123456789" (.-textContent node)))
                  (is (= "1" (.-opacity (js/getComputedStyle node))) "Pending text remains visible")
                  (is (.includes (.getPropertyValue (.-style node) "--monospace-font") (:initial-family opts)))
                  ;; Release the transport gate; the original effect uses the real browser font API.
                  (original (first @loads))
                  (await! (until! node "out"))
                  (is (= "loaded" (.-status face)))
                  (is (.includes (.getPropertyValue (.-style node) "--monospace-font") (:initial-family opts)))
                  (ended! span "font-fade-out")
                  (await! (support/settle!))
                  (is (= "out" (phase node)) "Child animations cannot complete the root fade")
                  (await! (tick! 215))
                  (is (= "0" (.-opacity (js/getComputedStyle node))))
                  (ended! node "font-fade-out")
                  (await! (until! node "in"))
                  (is (.includes (.getPropertyValue (.-style node) "--monospace-font") (:family opts)))
                  (is (seq @*hidden) "The swapped phase was committed before revealing")
                  (is (every? #{"0"} @*hidden) "New glyphs are painted only while transparent")
                  (is (.includes (.-fontFamily (js/getComputedStyle span)) (:family opts)))
                  ;; Courier New is optional, particularly on Linux browser runners.
                  ;; The native load/fade/identity assertions still run without it.
                  (when @*adjusted?
                    (is (< (js/Math.abs (- original-width (.-width (.getBoundingClientRect span)))) 0.2)
                        "The adjusted fallback keeps monospace advances stable")
                    (is (< (js/Math.abs (- original-height (.-height (.getBoundingClientRect span)))) 0.5)
                        "The adjusted fallback keeps vertical metrics stable"))
                  (is (identical? code (.querySelector node "code")))
                  (is (identical? span (.querySelector node "span")))
                  (is (.contains (.-classList node) "caller"))
                  (is (.contains (.-classList node) "other"))
                  (is (= "rgb(10, 20, 30)" (.-color (.-style node))))
                  (ended! node "font-fade-in")
                  (await! (until! node "ready"))
                  (is (not (.contains (.-classList node) "font-transition")))
                  (is (pos? @*ended) "The caller's Reagent handler remains active")
                  (await! (until! (second nodes) "ready"))
                  (is (= 1 (count @loads)))))
              (finally (.disconnect observer) (close!) (restore!)
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
                  (rf/dispatch [:font/loaded (font/request options) token false])
                  (await! (until! node "failed"))
                  (rf/dispatch [:font/loaded (font/request options) token true])
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
                (is (not (.contains (.-classList node) "font-transition")))
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
              (with-redefs [motion/reduced-motion? (constantly true)]
                (await! (support/render! root [<sample> opts {}]))
                (let [node (.querySelector element "pre")]
                  (await! (until! node "fallback"))
                  (let [[options token] (first @loads)]
                    (rf/dispatch [:font/loaded (font/request options) token true])
                    (await! (until! node "ready"))
                    (is (not (.contains (.-classList node) "font-transition")))
                    (await! (support/render! root nil))
                    (with-redefs [motion/reduced-motion? (constantly false)
                                  restore/local-document? (constantly true)]
                      (await! (support/render! root [<sample> opts {}]))
                      (let [returned (.querySelector element "pre")]
                        (await! (until! returned "ready"))
                        (is (not (.contains (.-classList returned) "font-transition"))
                            "A local return skips the fade even without reduced motion"))
                      (await! (support/render! root nil)))
                    (rf/dispatch [:font/swap (random-uuid)])
                    (rf/dispatch [:font/loaded (font/request options) token true])
                    (await! (support/settle!))
                    (is (not-any? #(= opts (:options %))
                                  (vals (get (await! (support/state-at! [:fonts])) :instances)))))))
              (finally (close!) (restore!) (.delete (.-fonts js/document) face)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
