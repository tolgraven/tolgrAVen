(ns tolgraven.browser-resources-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [tolgraven.browser-resources :as resources]))

(deftest optional-scripts-wait-for-load-two-painted-frames-and-idle-and-can-be-cancelled
  (doseq [ready-state ["loading" "complete"]]
    (testing ready-state
      (let [original-frame js/requestAnimationFrame
            original-cancel-frame js/cancelAnimationFrame
            original-idle js/requestIdleCallback
            original-cancel-idle js/cancelIdleCallback
            original-add (.-addEventListener js/window)
            original-remove (.-removeEventListener js/window)
            *frames (atom [])
            *idle (atom nil)
            *loaded (atom nil)
            *loads (atom 0)
            *stop (atom nil)]
        (try
          (js/Object.defineProperty js/document "readyState"
                                    #js {:configurable true :value ready-state})
          (set! js/requestAnimationFrame
                (fn [callback] (swap! *frames conj callback) (count @*frames)))
          (set! js/cancelAnimationFrame (fn [_]))
          (set! js/requestIdleCallback (fn [callback] (reset! *idle callback) 1))
          (set! js/cancelIdleCallback (fn [_]))
          ;; Do not dispatch a global load event: the Shadow runner also owns it.
          (set! (.-addEventListener js/window)
                (fn [event callback options]
                  (if (= event "load") (reset! *loaded callback)
                      (.call original-add js/window event callback options))))
          (set! (.-removeEventListener js/window)
                (fn [event callback]
                  (when-not (= event "load") (.call original-remove js/window event callback))))
          (reset! *stop (resources/after-page! #(swap! *loads inc)))
          (when (= ready-state "loading")
            (is (empty? @*frames))
            (@*loaded))
          (is (= 1 (count @*frames)))
          ((first @*frames))
          (is (= 2 (count @*frames)))
          (is (nil? @*idle))
          ((second @*frames))
          (is (zero? @*loads))
          (@*idle)
          (is (= 1 @*loads))
          (@*stop)
          (@*idle)
          (is (= 1 @*loads) "Cancelled work cannot acquire a script")
          (finally
            (when @*stop (@*stop))
            (js/Reflect.deleteProperty js/document "readyState")
            (set! js/requestAnimationFrame original-frame)
            (set! js/cancelAnimationFrame original-cancel-frame)
            (set! js/requestIdleCallback original-idle)
            (set! js/cancelIdleCallback original-cancel-idle)
            (set! (.-addEventListener js/window) original-add)
            (set! (.-removeEventListener js/window) original-remove)))))))
