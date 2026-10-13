(ns tolgraven.component-lifecycle-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.components.popover :as popover]
            [tolgraven.components.timer :as timer]
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

(deftest timeout-keeps-its-deadline-and-runs-the-latest-committed-callback
  (async done
    (let [original-timeout js/setTimeout
          original-clear js/clearTimeout
          *timers (atom [])
          *cleared (atom #{})
          *calls (atom [])]
      (set! js/setTimeout
        (fn [callback milliseconds & args]
          (if (= 9876 milliseconds)
            (let [id (js-obj)]
              (swap! *timers conj {:id id, :callback callback})
              id)
            (.apply original-timeout js/window (to-array (into [callback milliseconds] args))))))
      (set! js/clearTimeout
        (fn [id]
          (if (some #(identical? id (:id %)) @*timers)
            (swap! *cleared conj id)
            (original-clear id))))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [timer/<timeout> #(swap! *calls conj :old) 9876]))
                (is (= 1 (count @*timers)))
                (await! (support/render! root [timer/<timeout> #(swap! *calls conj :current) 1234]))
                (is (= 1 (count @*timers)) "Argument updates do not restart the mount deadline")
                (is (empty? @*calls) "Updating a callback must not run it as an effect cleanup")
                ((:callback (first @*timers)))
                (is (= [:current] @*calls))
                (finally (support/unmount! root)))
              (is (contains? @*cleared (:id (first @*timers))) "Unmount cancels the owned timer")))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! js/setTimeout original-timeout)
                      (set! js/clearTimeout original-clear)
                      (done)))))))
