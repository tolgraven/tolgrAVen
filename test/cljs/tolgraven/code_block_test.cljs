(ns tolgraven.code-block-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as re-frame]
            [reagent.impl.batching :as batching]
            [tolgraven.react :as rf]
            [tolgraven.components.code-block :as code]
            [tolgraven.loader :as loader]
            [tolgraven.component.restore :as restore]
            [tolgraven.browser-resources :as resources]
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
