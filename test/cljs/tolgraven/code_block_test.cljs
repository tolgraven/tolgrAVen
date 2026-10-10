(ns tolgraven.code-block-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as re-frame]
            [reagent.impl.batching :as batching]
            [reagent.dom.client :as dom]
            [reagent.dom.server :as server]
            [tolgraven.render-context :as context]
            [tolgraven.modules.highlight.module :as highlight]
            [tolgraven.react :as rf]
            [tolgraven.components.code-block :as code]
            [tolgraven.modules.highlight.views :as highlighter]
            [tolgraven.loader :as loader]
            [tolgraven.component.restore :as restore]
            [tolgraven.browser-resources :as resources]
            [tolgraven.listener :as listener]
            [tolgraven.test-support :as support]))

(deftest spa-code-acquires-immediately-and-controls-preserve-content
  (async done
    (let [restore! (re-frame/make-restore-fn)
          old-acquire loader/acquire-code!
          before @restore/*context
          *calls (atom [])
          *copied (atom nil)]
      (reset! restore/*context {:hydrate? false})
      (set! loader/acquire-code!
        (fn [id]
          (swap! *calls conj id)
          (js/Promise.resolve {:view {:code-block (fn [text & _] [:pre [:code text]])}})))
      (rf/reg-fx :code-block/copy
        (fn [{:keys [text complete!]}] (reset! *copied text) (complete! "Copied")))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [code/<code-block> "(inc 1)" :language "clojure"]))
                (await! (support/wait-for! #(seq @*calls)))
                (await! (support/settle!))
                (is (= [:highlight] @*calls))
                (let [pre (.querySelector element "pre")
                      buttons (.querySelectorAll element "button")]
                  (.click (aget buttons 0))
                  (await! (support/wait-for! #(= "Copied" (.-textContent (aget buttons 0)))))
                  (is (= "(inc 1)" @*copied))
                  (.click (aget buttons 1))
                  (await! (support/wait-for! #(.querySelector element ".code-block-wrapped")))
                  (is (identical? pre (.querySelector element "pre")))
                  (is (= "true" (.getAttribute (aget buttons 1) "aria-pressed"))))
                (await! (support/render! root [code/<code-block> "(inc 2)" :language "clojure"]))
                (is (= "(inc 2)" (.-textContent (.querySelector element "code"))))
                (is (= [:highlight] @*calls) "Updating code reuses the acquired formatter")
                (finally (support/unmount! root)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! loader/acquire-code! old-acquire)
                      (reset! restore/*context before) (restore!) (done)))))))

(deftest unmount-cancels-the-owned-after-page-gate
  (async done
    (let [old-after-page resources/after-page!
          old-acquire loader/acquire-code!
          before @restore/*context
          before-flush batching/react-flush
          *cancelled? (atom false)
          *calls (atom [])]
      (reset! restore/*context {:hydrate? true})
      (set! resources/after-page! (fn [_] #(reset! *cancelled? true)))
      (set! loader/acquire-code! #(swap! *calls conj %))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [code/<code-block> "(inc 1)"]))
                (is (empty? @*calls))
                (is (identical? batching/react-flush rf/start-transition))
                (finally (support/unmount! root)))
              (is @*cancelled?)
              (is (identical? batching/react-flush before-flush))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! resources/after-page! old-after-page)
                      (set! loader/acquire-code! old-acquire)
                      (reset! restore/*context before) (done)))))))

(deftest navigation-releases-pending-initial-hydration-before-module-acquisition
  (async done
    (let [old-after-page resources/after-page!
          before @restore/*context
          before-flush batching/react-flush]
      (restore/begin! {:hydrate? true})
      (set! resources/after-page! (fn [_] (fn [])))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [code/<code-block> "(inc 1)"]))
                (is (identical? batching/react-flush rf/start-transition))
                (restore/navigate! "/different-page")
                (is (identical? batching/react-flush before-flush))
                (finally (support/unmount! root)))
              (is (identical? batching/react-flush before-flush))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! resources/after-page! old-after-page)
                      (reset! restore/*context before)
                      (done)))))))

(defn pointer! [element type x y & [pointer-type]]
  (.dispatchEvent element
    (js/PointerEvent. type #js {:bubbles true
                                :pointerId 1
                                :isPrimary true
                                :pointerType (or pointer-type "mouse")
                                :button 0
                                :clientX x
                                :clientY y})))

(deftest copy-gestures-cancel-selection-drag-exit-and-touch-scroll
  (async done
    (let [restore! (re-frame/make-restore-fn)
          old-acquire loader/acquire-code!
          before @restore/*context
          *copies (atom [])]
      (reset! restore/*context {:hydrate? false})
      (set! loader/acquire-code!
        (fn [_] (js/Promise.resolve {:view {:code-block highlighter/<code-block>}})))
      (rf/reg-fx :code-block/copy
        (fn [{:keys [text complete!]}] (swap! *copies conj text) (complete! "Copied")))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))
                  text "(inc 1)\n(inc 2)\n(inc 3)"]
              (.appendChild (.-body js/document) element)
              (try
                (await! (support/render! root [code/<code-block> text
                                              {:language "clojure"
                                               :line-numbers? true
                                               :foldable? true
                                               :folded? true
                                               :fold-lines 1}]))
                (await! (support/wait-for! #(.querySelector element ".react-syntax-highlighter-line-number")))
                (let [box (.querySelector element ".code-block")
                      token (.querySelector box "code span")]
                  ;; Fixed geometry isolates gesture logic from test runner layout.
                  (set! (.-getBoundingClientRect box)
                    (fn [] #js {:left 0 :right 200 :top 0 :bottom 200}))
                  (pointer! token "pointerdown" 10 10)
                  (pointer! token "pointerup" 10 10)
                  (await! (support/wait-for! #(= 1 (count @*copies))))
                  (is (= [text] @*copies) "Copies full source, excluding line numbers and folded presentation")
                  (doseq [[kind pointer-type] [[:drag "mouse"] [:leave "mouse"] [:cancel "mouse"] [:drag "touch"] [:leave "touch"]]]
                    (pointer! token "pointerdown" 10 10 pointer-type)
                    (case kind
                      :drag (pointer! token "pointermove" 20 10 pointer-type)
                      :leave (do (pointer! box "pointerout" 10 10 pointer-type)
                                 (pointer! token "pointermove" 220 10 pointer-type))
                      :cancel (pointer! token "pointercancel" 10 10 pointer-type))
                    (pointer! token "pointerup" 10 10 pointer-type))
                  (await! (support/settle!))
                  (is (= 1 (count @*copies)) "Cancelled gestures remain cancelled after reentry")
                  (let [selection (.getSelection js/window)
                        range (.createRange js/document)]
                    (.selectNodeContents range (.querySelector box "code"))
                    (.removeAllRanges selection)
                    (.addRange selection range)
                    (pointer! token "pointerdown" 10 10)
                    (pointer! token "pointerup" 10 10)
                    (await! (support/settle!))
                    (is (= 1 (count @*copies)) "Selecting code never overwrites the clipboard")
                    (.removeAllRanges selection))
                  (.click (.querySelector box "button[aria-expanded]"))
                  (await! (support/wait-for! #(nil? (.querySelector element ".code-block-folded"))))
                  (is (identical? token (.querySelector box "code span")) "Folding retains highlighted nodes")
                  (is (= 1 (count @*copies)) "Presentation controls do not copy"))
                (await! (support/render! root [code/<code-block> "echo \"hello\"" :inline? true]))
                (await! (support/wait-for! #(.querySelector element ".code-snippet code span")))
                (let [button (.querySelector element ".code-copy")]
                  (.click button)
                  (await! (support/wait-for! #(= 2 (count @*copies))))
                  (is (= "echo \"hello\"" (last @*copies)))
                  (await! (support/wait-for! #(= "Copied" (.getAttribute button "aria-label"))))
                  (is (= "✓" (.-textContent button)))
                  (is (nil? (.querySelector element "pre"))))
                (finally (support/unmount! root) (.remove element)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! loader/acquire-code! old-acquire)
                      (reset! restore/*context before)
                      (restore!) (done)))))))

(deftest copying-server-tokens-does-not-wait-for-the-formatter
  (async done
    (let [restore! (re-frame/make-restore-fn)
          old-acquire loader/acquire-code!
          old-after-page resources/after-page!
          before @restore/*context
          before-interactive @context/*interactive?
          *calls (atom [])
          *copies (atom [])
          *release (atom nil)]
      (reset! context/*interactive? false)
      (restore/begin! {:hydrate? true})
      (set! resources/after-page! (fn [release!] (reset! *release release!) (fn [])))
      (set! loader/acquire-code!
        (fn [id]
          (swap! *calls conj id)
          (js/Promise.resolve {:view {:code-block highlighter/<code-block>}})))
      (rf/reg-fx :code-block/copy
        (fn [{:keys [text complete!]}] (swap! *copies conj text) (complete! "Copied")))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  view [code/<code-block> "(inc 1)" :language "clojure"]]
              (.appendChild (.-body js/document) element)
              (set! (.-innerHTML element)
                (binding [context/*server?* true
                          context/*modules* {:highlight highlight/spec}]
                  (server/render-to-string view)))
              (let [token (.querySelector element "code span")
                    root (dom/hydrate-root element view)]
                (try
                  (await! (support/wait-for! #(some? @*release)))
                  (await! (support/settle!))
                  (let [box (.querySelector element ".code-block")
                        bounds (.getBoundingClientRect box)
                        x (+ (.-left bounds) 2)
                        y (+ (.-top bounds) 2)]
                    (pointer! token "pointerdown" x y)
                    (pointer! token "pointerup" x y))
                  (await! (support/wait-for! #(seq @*copies)))
                  (is (= ["(inc 1)"] @*copies))
                  (is (empty? @*calls) "Copying does not acquire the deferred formatter")
                  (is (identical? token (.querySelector element "code span")))
                  (finally (dom/unmount root) (.remove element)))
                (is (empty? (filter #(= :code-block (:owner %)) (vals @listener/*bindings)))
                    "Unmount releases the native SSR pointer adapter"))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! loader/acquire-code! old-acquire)
                      (set! resources/after-page! old-after-page)
                      (reset! context/*interactive? before-interactive)
                      (reset! restore/*context before)
                      (restore!) (done)))))))

(deftest copy-feedback-reports-failure-resets-and-discards-old-source-results
  (async done
    (let [restore! (re-frame/make-restore-fn)
          old-acquire loader/acquire-code!
          before @restore/*context
          *requests (atom [])]
      (reset! restore/*context {:hydrate? false})
      (set! loader/acquire-code!
        (fn [_] (js/Promise.resolve {:view {:code-block highlighter/<code-block>}})))
      (rf/reg-fx :code-block/copy #(swap! *requests conj %))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [code/<code-block> "old" :inline? true]))
                (.click (.querySelector element ".code-copy"))
                (await! (support/wait-for! #(= 1 (count @*requests))))
                (await! (support/render! root [code/<code-block> "new" :inline? true]))
                ((:complete! (first @*requests)) "Copied")
                (await! (support/settle!))
                (is (= "Copy code" (.getAttribute (.querySelector element ".code-copy") "aria-label"))
                    "A clipboard completion for old contents cannot label new contents as copied")
                (.click (.querySelector element ".code-copy"))
                (await! (support/wait-for! #(= 2 (count @*requests))))
                ((:complete! (second @*requests)) "Copy failed")
                (await! (support/wait-for! #(= "Copy failed" (.-textContent (.querySelector element "[role=status]")))))
                (is (= "!" (.-textContent (.querySelector element ".code-copy"))))
                (await! (support/wait-for! #(= "Copy code" (.getAttribute (.querySelector element ".code-copy") "aria-label"))))
                (is (= "" (.-textContent (.querySelector element "[role=status]"))))
                (finally (support/unmount! root)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! loader/acquire-code! old-acquire)
                      (reset! restore/*context before)
                      (restore!) (done)))))))
