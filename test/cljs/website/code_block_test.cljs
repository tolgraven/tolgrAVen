(ns website.code-block-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]



            [website.render-context :as context]
            [website.modules.highlight.module :as highlight]

            [website.modules.highlight.code-block :as code]
            [website.modules.highlight.views :as highlighter]
            [website.loader :as loader]
            [website.component.restore :as restore]
            [website.browser-resources :as resources]
            [website.listener :as listener]
            [website.test-support :as support]))

(deftest spa-code-acquires-immediately-and-controls-preserve-content
  (async done
    (let [restore! (r/make-restore-fn)
          old-acquire loader/acquire-code!
          before @restore/*context
          *calls (atom [])
          *copied (atom nil)]
      (reset! restore/*context {:hydrate? false})
      (set! loader/acquire-code!
        (fn [id]
          (swap! *calls conj id)
          (js/Promise.resolve {:view {:formatted-code (fn [text & _] [:pre [:code text]])}})))
      (r/reg-fx :code-block/copy
        (fn [{:keys [text complete!]}] (reset! *copied text) (complete! "Copied")))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [code/<code-block> "(inc 1)" :language "clojure"]))
                (await! (support/wait-for! #(seq @*calls)))
                (await! (support/settle!))
                (is (= [:highlight] @*calls))
                (is (empty? (filter #(= :code-block (:owner %)) (vals @listener/*bindings)))
                    "SPA code uses Reagent handlers without native document bindings")
                (let [pre (.querySelector element "pre")
                      buttons (.querySelectorAll element "button")]
                  (is (.querySelector (aget buttons 0) "i.fa-copy"))
                  (is (.querySelector (aget buttons 1) "i.fa-align-left"))
                  (is (= "Wrap lines" (.getAttribute (aget buttons 1) "aria-label")))
                  (is (empty? (.-textContent (.querySelector element ".code-block-controls"))))
                  (is (= "0" (.getAttribute (.querySelector element ".code-block") "tabindex")))
                  (.click (aget buttons 0))
                  (await! (support/wait-for! #(= "Copied" (.getAttribute (aget buttons 0) "aria-label"))))
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

(deftest icon-labels-follow-hover-focus-feedback-and-owned-lifetime
  (async done
    (let [restore! (r/make-restore-fn)
          old-acquire loader/acquire-code!
          before @restore/*context
          element (.createElement js/document "div")]
      (reset! restore/*context {:hydrate? false})
      (set! loader/acquire-code!
        (fn [_]
          (js/Promise.resolve {:view {:formatted-code (fn [text & _] [:pre [:code text]])}})))
      (r/reg-fx :code-block/copy (fn [{:keys [complete!]}] (complete! "Copied")))
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (let [root (await! (support/create-root! element))
                  tooltip #(when-let [id (.getAttribute % "aria-describedby")]
                             (.getElementById js/document id))
                  owners #(filter (fn [binding] (= :action-label (:owner binding)))
                                  (vals @listener/*bindings))]
              (try
                (await! (support/render! root [code/<code-block> "(inc 1)"]))
                (await! (support/settle!))
                (let [copy (.querySelector element ".code-copy")
                      wrap (.querySelector element ".code-wrap")]
                  (is (nil? (.getAttribute copy "title")) "No duplicate native tooltip")
                  (is (nil? (tooltip copy)))
                  (is (empty? (owners)) "Closed labels install no listeners")
                  (.dispatchEvent copy (js/PointerEvent. "pointerover" #js {:bubbles true}))
                  (await! (support/settle!))
                  (is (= "Copy code" (.-textContent (tooltip copy))))
                  (is (= "tooltip" (.getAttribute (tooltip copy) "role")))
                  (is (not (.contains element (tooltip copy))) "Label escapes code clipping")
                  (.click copy)
                  (await! (support/wait-for! #(= "Copied" (.-textContent (tooltip copy)))))
                  (.dispatchEvent copy (js/PointerEvent. "pointerout" #js {:bubbles true}))
                  (await! (support/settle!))
                  (is (nil? (tooltip copy)))
                  (is (empty? (owners)))
                  (.focus wrap)
                  (await! (support/settle!))
                  (is (= "Wrap lines" (.-textContent (tooltip wrap))))
                  (.click wrap)
                  (await! (support/settle!))
                  (is (= "true" (.getAttribute wrap "aria-pressed")))
                  (.dispatchEvent wrap (js/KeyboardEvent. "keydown" #js {:key "Escape", :bubbles true}))
                  (await! (support/settle!))
                  (is (nil? (tooltip wrap)))
                  (is (identical? wrap (.-activeElement js/document)) "Dismissal retains focus")
                  (.blur wrap)
                  (.focus wrap)
                  (await! (support/settle!))
                  (.dispatchEvent js/window (js/Event. "resize"))
                  (await! (support/settle!))
                  (is (nil? (tooltip wrap)) "Geometry changes dismiss the label"))
                (await! (support/render! root
                         [:div {:style {:position "fixed", :left "0rem", :top "1rem", :width "90vw"}}
                          [code/<code-block> "inline" :inline? true]]))
                (let [copy (.querySelector element ".code-copy")]
                  (.focus copy)
                  (await! (support/settle!))
                  (is (= "Copy code" (.-textContent (tooltip copy))))
                  (is (seq (owners)))
                  (r/reg-fx :code-block/copy (fn [{:keys [complete!]}] (complete! "Copy unavailable")))
                  (.click copy)
                  (await! (support/wait-for! #(= "Copy unavailable" (.-textContent (tooltip copy)))))
                  (await! (support/settle!))
                  (let [bounds (.getBoundingClientRect (tooltip copy))]
                    (is (<= 0 (.-left bounds)) "Long feedback near the left edge stays visible")
                    (is (<= (.-right bounds) (.-innerWidth js/window)))))
                (finally (support/unmount! root)))
              (is (nil? (.querySelector js/document ".action-label")))
              (is (empty? (owners)) "Unmount releases every label listener")))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (.remove element)
                      (set! loader/acquire-code! old-acquire)
                      (reset! restore/*context before)
                      (restore!)
                      (done)))))))

(deftest unmount-cancels-the-owned-after-page-gate
  (async done
    (let [old-after-page resources/after-page!
          old-acquire loader/acquire-code!
          before @restore/*context
          before-flush (r/batching-flush)
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
                (is (identical? (r/batching-flush) r/start-transition))
                (finally (support/unmount! root)))
              (is @*cancelled?)
              (is (identical? (r/batching-flush) before-flush))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! resources/after-page! old-after-page)
                      (set! loader/acquire-code! old-acquire)
                      (reset! restore/*context before) (done)))))))

(deftest navigation-releases-pending-initial-hydration-before-module-acquisition
  (async done
    (let [old-after-page resources/after-page!
          before @restore/*context
          before-flush (r/batching-flush)]
      (restore/begin! {:hydrate? true})
      (set! resources/after-page! (fn [_] (fn [])))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [code/<code-block> "(inc 1)"]))
                (is (identical? (r/batching-flush) r/start-transition))
                (restore/navigate! "/different-page")
                (is (identical? (r/batching-flush) before-flush))
                (finally (support/unmount! root)))
              (is (identical? (r/batching-flush) before-flush))))
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
    (let [restore! (r/make-restore-fn)
          old-acquire loader/acquire-code!
          before @restore/*context
          *copies (atom [])]
      (reset! restore/*context {:hydrate? false})
      (set! loader/acquire-code!
        (fn [_] (js/Promise.resolve {:view {:formatted-code highlighter/<formatted-code>}})))
      (r/reg-fx :code-block/copy
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
                  ;; A completed tap stays valid when the released pointer moves
                  ;; away during the multi-click confirmation window.
                  (pointer! token "pointermove" 30 10)
                  (pointer! box "pointerout" 30 10)
                  (await! (support/wait-for! #(= 1 (count @*copies))))
                  (is (= [text] @*copies) "Copies full source, excluding line numbers and folded presentation")
                  (pointer! token "pointerdown" 10 10)
                  (pointer! token "pointerup" 10 10)
                  (pointer! token "pointerdown" 10 10)
                  (pointer! token "pointerup" 10 10)
                  (.dispatchEvent token (js/MouseEvent. "dblclick" #js {:bubbles true :detail 2}))
                  (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 550))))
                  (is (= 1 (count @*copies)) "A double-click selection never copies either click")
                  (doseq [[kind pointer-type] [[:drag "mouse"] [:leave "mouse"] [:cancel "mouse"] [:drag "touch"] [:leave "touch"]]]
                    (pointer! token "pointerdown" 10 10 pointer-type)
                    (case kind
                      :drag (pointer! token "pointermove" 20 10 pointer-type)
                      :leave (do (pointer! box "pointerout" 10 10 pointer-type)
                                 (pointer! token "pointermove" 220 10 pointer-type))
                      :cancel (pointer! token "pointercancel" 10 10 pointer-type))
                    (pointer! token "pointerup" 10 10 pointer-type))
                  (await! (support/settle!))
                  (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 550))))
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
    (let [restore! (r/make-restore-fn)
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
          (js/Promise.resolve {:view {:formatted-code highlighter/<formatted-code>}})))
      (r/reg-fx :code-block/copy
        (fn [{:keys [text complete!]}] (swap! *copies conj text) (complete! "Copied")))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  view [code/<code-block> "(inc 1)" :language "clojure"]]
              (.appendChild (.-body js/document) element)
              (set! (.-innerHTML element)
                (binding [context/*server?* true
                          context/*modules* {:highlight highlight/spec}]
                  (r/render-to-string view)))
              (let [token (.querySelector element "code span")
                    root (r/hydrate-root element view)]
                (try
                  (await! (support/wait-for! #(some? @*release)))
                  (await! (support/settle!))
                  (let [box (.querySelector element ".code-block")
                        bounds (.getBoundingClientRect box)
                        x (+ (.-left bounds) 2)
                        y (+ (.-top bounds) 2)]
                    (pointer! token "pointerdown" x y)
                    (pointer! token "pointerup" x y)
                    (pointer! token "pointermove" (+ x 20) y)
                    (pointer! box "pointerout" (+ x 20) y))
                  (await! (support/wait-for! #(seq @*copies)))
                  (is (= ["(inc 1)"] @*copies))
                  (.click (.querySelector element ".code-copy"))
                  (await! (support/wait-for! #(= 2 (count @*copies))))
                  (is (= ["(inc 1)" "(inc 1)"] @*copies)
                      "The sibling Copy button hydrates before the formatter")
                  (is (empty? @*calls) "Copying does not acquire the deferred formatter")
                  (is (identical? token (.querySelector element "code span")))
                  (@*release)
                  (await! (support/wait-for!
                            #(and (seq @*calls)
                                  (empty? (filter (fn [binding] (= :code-block (:owner binding)))
                                                  (vals @listener/*bindings))))))
                  (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 550))))
                  (is (= ["(inc 1)" "(inc 1)"] @*copies) "Hydration never replays an already handled copy gesture")
                  (is (identical? token (.querySelector element "code span")))
                  (finally (r/unmount root) (.remove element)))
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
    (let [restore! (r/make-restore-fn)
          old-acquire loader/acquire-code!
          before @restore/*context
          *requests (atom [])]
      (reset! restore/*context {:hydrate? false})
      (set! loader/acquire-code!
        (fn [_] (js/Promise.resolve {:view {:formatted-code highlighter/<formatted-code>}})))
      (r/reg-fx :code-block/copy #(swap! *requests conj %))
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

(defn ^:export show-controls! []
  (restore/begin! {:hydrate? false})
  (-> (support/create-root! (.getElementById js/document "code-controls"))
      (.then #(support/render! %
                [:main.code-controls-demo
                 [:h1 "Code actions"]
                 [:p "Inline source " [code/<code-block> "(inc 1)" :inline? true] " stays in the text flow."]
                 [code/<code-block> "(defn example []\n  (str \"Hover or focus the controls\"\n       \" — this intentionally long line can wrap at the edge of its code block.\"))"
                  :language "clojure"]]))))
