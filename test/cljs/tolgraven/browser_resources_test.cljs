(ns tolgraven.browser-resources-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [tolgraven.browser-resources :as resources]
            [tolgraven.component.restore :as restore]
            [tolgraven.render-context :as context]))

(deftest optional-scripts-wait-for-load-two-painted-frames-and-idle-and-can-be-cancelled
  (with-redefs [context/*interactive? (atom true)
                restore/*context (atom {})
                restore/*pending-layout (atom #{})]
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
              (set! (.-removeEventListener js/window) original-remove))))))))

(deftest optional-scripts-wait-for-hydration-and-pending-page-bindings
  (with-redefs [context/*interactive? (atom true)
                restore/*context (atom {:hydrate? true})
                restore/*pending-layout (atom #{:pending})]
    (let [original-frame js/requestAnimationFrame
          original-cancel-frame js/cancelAnimationFrame
          original-idle js/requestIdleCallback
          original-cancel-idle js/cancelIdleCallback
          original-timeout js/setTimeout
          original-clear-timeout js/clearTimeout
          *frame (atom nil)
          *idle (atom nil)
          *retry (atom nil)
          *loads (atom 0)
          *stop (atom nil)
          painted-idle! (fn [] (@*frame) (@*frame) (@*idle))]
      (try
        (js/Object.defineProperty js/document "readyState"
                                  #js {:configurable true :value "complete"})
        (set! js/requestAnimationFrame (fn [callback] (reset! *frame callback) 1))
        (set! js/cancelAnimationFrame (fn [_]))
        (set! js/requestIdleCallback (fn [callback] (reset! *idle callback) 1))
        (set! js/cancelIdleCallback (fn [_]))
        (set! js/setTimeout (fn [callback _] (reset! *retry callback) 1))
        (set! js/clearTimeout (fn [_]))
        (reset! *stop (resources/after-page! #(swap! *loads inc)))
        (painted-idle!)
        (is (zero? @*loads) "Even an interactive root cannot bypass pending hydration")
        (reset! restore/*context {:hydrate? false})
        (reset! context/*interactive? false)
        (@*retry)
        (painted-idle!)
        (is (zero? @*loads) "Hydration completion must also enable interaction")
        (reset! context/*interactive? true)
        (@*retry)
        (painted-idle!)
        (is (zero? @*loads) "Mounted page bindings must settle first")
        (reset! restore/*pending-layout #{})
        (@*retry)
        (painted-idle!)
        (is (= 1 @*loads))
        (finally
          (when @*stop (@*stop))
          (js/Reflect.deleteProperty js/document "readyState")
          (set! js/requestAnimationFrame original-frame)
          (set! js/cancelAnimationFrame original-cancel-frame)
          (set! js/requestIdleCallback original-idle)
          (set! js/cancelIdleCallback original-cancel-idle)
          (set! js/setTimeout original-timeout)
          (set! js/clearTimeout original-clear-timeout))))))
