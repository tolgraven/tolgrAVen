(ns tolgraven.component-lifecycle-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.components.popover :as popover]
            [tolgraven.test-support :as support]))

(r/defc <suspended> [gate] (throw gate))

(deftest popover-observers-belong-to-committed-open-surfaces
  (async done
    (let [original js/ResizeObserver
          *created (atom [])
          *stopped (atom #{})
          gate (js/Promise. (fn [_ _]))]
      (set! js/ResizeObserver
        (fn [_]
          (let [id (random-uuid)]
            (swap! *created conj id)
            #js {:observe (fn [_])
                 :disconnect #(swap! *stopped conj id)})))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root
                          [rf/suspense {:fallback (r/as-element [:p "Waiting"])}
                           [:<>
                            [popover/<popover> {:open? true} [:p "Abandoned"]]
                            [<suspended> gate]]]))
                (is (= "Waiting" (.-textContent element)))
                (is (empty? @*created) "A discarded render must not create external observers")
                (await! (support/render! root [popover/<popover> {:open? false} [:p "Closed"]]))
                (is (empty? @*created) "Closed surfaces own no observer")
                (await! (support/render! root
                          [:> rf/strict-mode
                           [popover/<popover> {:open? true} [:p "Open"]]]))
                (is (>= (count @*created) 2) "StrictMode repeats setup with fresh owners")
                (is (= 1 (count (remove @*stopped @*created))))
                (await! (support/render! root
                          [:> rf/strict-mode
                           [popover/<popover> {:open? false} [:p "Closed"]]]))
                (is (every? @*stopped @*created) "Closing releases every committed owner")
                (await! (support/render! root
                          [:> rf/strict-mode
                           [popover/<popover> {:open? true} [:p "Reopened"]]]))
                (is (= 1 (count (remove @*stopped @*created))))
                (finally (support/unmount! root)))
              (is (every? @*stopped @*created) "Unmount releases the reopened owner")))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! js/ResizeObserver original) (done)))))))
