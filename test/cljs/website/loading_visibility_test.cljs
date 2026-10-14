(ns website.loading-visibility-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.component.loading :as loading]
            [website.test-support :as support]))

(r/defc <sample> [mounted! stopped!]
  (r/use-effect (fn [] (mounted!) stopped!) [])
  [:div {:data-loading-sample true :style {:height "8rem"}}
   [loading/<article>]])

(r/defc <fixture> [top mounted! stopped!]
  [:div {:style {:position "fixed" :top top :left "1rem" :width "20rem"}}
   [loading/<rendered> {:form [<sample> mounted! stopped!]}]])

(deftest native-viewport-unmounts-loading-body-without-collapsing-its-slot
  (async done
    (let [element (.createElement js/document "div")
          original js/IntersectionObserver
          *observers (atom [])
          *mounts (atom 0)
          *stops (atom 0)
          mounted! #(swap! *mounts inc)
          stopped! #(swap! *stops inc)
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (set! js/IntersectionObserver
        (fn [callback options]
          (let [observer (js/Reflect.construct original #js [callback options])]
            (swap! *observers conj observer)
            observer)))
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [<fixture> "1rem" mounted! stopped!]))
              (await! (support/wait-for! #(= 1 @*mounts)))
              (let [slot (.querySelector element ".component-render-skeleton")
                    height (.-height (.getBoundingClientRect slot))]
                (is (= 1 (count @*observers)) "Nested article/line primitives share their outer observer")
                (await! (support/render! root [<fixture> "200vh" mounted! stopped!]))
                (await! (support/wait-for! #(= 1 @*stops) "Offscreen loading content stayed mounted"))
                (is (nil? (.querySelector element "[data-loading-sample]")))
                (is (< (js/Math.abs (- height (.-height (.getBoundingClientRect slot)))) 1))
                (is (.endsWith (.-height (.-style slot)) "em"))
                (await! (support/render! root [<fixture> "1rem" mounted! stopped!]))
                (await! (support/wait-for! #(= 2 @*mounts) "Visible loading content did not resume"))
                (is (identical? slot (.querySelector element ".component-render-skeleton")))
                (is (not (.hasAttribute slot "data-skeleton-hidden"))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (set! js/IntersectionObserver original)
                      (is (= @*mounts @*stops) "Every loading body releases its lifecycle")
                      (done)))))))

(deftest hidden-document-releases-loading-content-and-resumes-on-return
  (async done
    (let [element (.createElement js/document "div")
          descriptor (js/Object.getOwnPropertyDescriptor js/document "hidden")
          *hidden? (atom false)
          *mounts (atom 0)
          *stops (atom 0)
          *root (atom nil)]
      (js/Object.defineProperty js/document "hidden" #js {:configurable true :get #(deref *hidden?)})
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [<fixture> "1rem" #(swap! *mounts inc) #(swap! *stops inc)]))
              (await! (support/wait-for! #(= 1 @*mounts)))
              (reset! *hidden? true)
              (.dispatchEvent js/document (js/Event. "visibilitychange"))
              (await! (support/wait-for! #(= 1 @*stops)))
              (is (nil? (.querySelector element "[data-loading-sample]")))
              (reset! *hidden? false)
              (.dispatchEvent js/document (js/Event. "visibilitychange"))
              (await! (support/wait-for! #(= 2 @*mounts)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (if descriptor
                        (js/Object.defineProperty js/document "hidden" descriptor)
                        (js/Reflect.deleteProperty js/document "hidden"))
                      (is (= @*mounts @*stops))
                      (done)))))))

(deftest inline-reservation-preserves-supplied-padding-and-both-box-models
  (async done
    (let [element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (doseq [box-sizing ["content-box" "border-box"]
                      width ["10rem" nil]]
                (let [form (fn [top]
                             [:div {:style {:position "fixed" :top top :left "1rem"}}
                              [loading/<placeholder>
                               {:loading-tag :span
                                :loading-prefab :lines
                                :classes "inline-reservation"
                                :loading-props {:style (cond-> {:display "inline-flex"
                                                                :height "3rem"
                                                                :padding ".5rem"
                                                                :box-sizing box-sizing}
                                                         width (assoc :width width))}}]])]
                  (await! (support/render! root (form "1rem")))
                  (await! (support/wait-for! #(.querySelector element ".component-skeleton-lines")))
                  (let [slot (.querySelector element ".inline-reservation")
                        rect (.getBoundingClientRect slot)]
                    (await! (support/render! root (form "200vh")))
                    (await! (support/wait-for! #(.hasAttribute slot "data-skeleton-hidden")))
                    (is (nil? (.querySelector element ".component-skeleton-lines")))
                    (is (< (js/Math.abs (- (.-height rect) (.-height (.getBoundingClientRect slot)))) 1) box-sizing)
                    (is (< (js/Math.abs (- (.-width rect) (.-width (.getBoundingClientRect slot)))) 1) box-sizing)
                    (is (= "0.5rem" (.-padding (.-style slot))))
                    (is (.endsWith (.-width (.-style slot)) "em"))
                    (await! (support/render! root (form "1rem")))
                    (await! (support/wait-for! #(.querySelector element ".component-skeleton-lines"))))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (done)))))))

(deftest an-empty-placeholder-keeps-its-zero-height-offscreen
  (async done
    (let [element (.createElement js/document "div")
          *root (atom nil)
          form (fn [top]
                 [:div {:style {:position "fixed" :top top :left "1rem" :width "20rem"}}
                  [loading/<placeholder> {:props {:id "empty-loading"}}]])]
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root (form "1rem")))
              (let [slot (.querySelector element "#empty-loading")]
                (is (zero? (.-height (.getBoundingClientRect slot))))
                (await! (support/render! root (form "200vh")))
                (await! (support/wait-for! #(.hasAttribute slot "data-skeleton-hidden")))
                (is (= "0em" (.-height (.-style slot))))
                (is (zero? (.-height (.getBoundingClientRect slot)))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (done)))))))
