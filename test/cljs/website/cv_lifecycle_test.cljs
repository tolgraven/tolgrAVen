(ns website.cv-lifecycle-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.editor-visual]
            [website.events]
            [website.component.restore :as restore]
            [website.modules.cv.pages :as pages]
            [website.modules.cv.views :as cv]
            [website.test-support :as support]))

(deftest cv-demo-keeps-its-mounted-timing-and-releases-actions-on-unmount
  (async done
    (let [restore-db! (r/make-restore-fn)
          context @restore/*context
          scroll-y (.-scrollY js/window)
          active-element (.-activeElement js/document)
          original-set-timeout js/setTimeout
          original-clear-timeout js/clearTimeout
          schedule! (r/get-handler :fx :dispatch-in/set)
          element (.createElement js/document "div")
          *root (atom nil)
          *scheduling? (atom false)
          *next-id (atom 0)
          *owned (atom #{})
          *pending (atom {})
          *scheduled (atom [])
          *delivered (atom [])
          *now (atom 0)
          drive! (fn [time]
                   (go-promise
                     (let [jobs (sort-by (comp :at val) (filter #(<= (:at (val %)) time) @*pending))
                           delivered (count @*delivered)]
                       (doseq [[id {:keys [callback]}] jobs]
                         (swap! *pending dissoc id)
                         (callback))
                       (reset! *now time)
                       (when (seq jobs)
                         (await! (support/wait-for! #(>= (count @*delivered) (+ delivered (count jobs)))
                                                   "CV timer callbacks did not reach their real events")))
                       (await! (support/settle!)))))]
      ;; Substitute only the original timer effect's native clock. Its registered
      ;; events, callbacks, DOM refs, scroll and focus effects remain unchanged.
      (set! js/setTimeout
            (fn [callback milliseconds & args]
              (if (and @*scheduling? (#{2000 3000 4500 5500} milliseconds))
                (let [id (swap! *next-id dec)]
                  (swap! *owned conj id)
                  (swap! *scheduled conj milliseconds)
                  (swap! *pending assoc id {:at (+ @*now milliseconds)
                                            :callback #(.apply callback nil (to-array args))})
                  id)
                (.apply original-set-timeout js/window (to-array (into [callback milliseconds] args))))))
      (set! js/clearTimeout
            (fn [id]
              (if (contains? @*owned id)
                (swap! *pending dissoc id)
                (original-clear-timeout id))))
      (r/reg-fx :dispatch-in/set
                (fn [arguments]
                  (reset! *scheduling? true)
                  (try (schedule! arguments) (finally (reset! *scheduling? false)))))
      (r/reg-cofx :ls #(assoc % :ls {}))
      (r/reg-fx :ls (fn [_]))
      (r/reg-global-interceptor
        (r/->interceptor
          :id :cv-lifecycle-test/actions
          :after (fn [context]
                   (let [event (get-in context [:coeffects :event])]
                     (when (or (#{:scroll/by :focus-element} (first event))
                               (and (= :state (first event)) (= [:fullscreen :cv] (second event))))
                       (swap! *delivered conj event)))
                   context)))
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (let [response (await! (js/fetch "/fixtures/editor-seed.json"))
                  _ (when-not (.-ok response) (throw (js/Error. "Missing local CV test seed")))
                  seed (js->clj (await! (.json response)) :keywordize-keys true)
                  root (await! (support/create-root! element))]
              (reset! *root root)
              (doseq [mounted? [true false]]
                (r/dispatch-sync [:editor-visual/install seed false])
                (r/dispatch-sync [:state [:cv :visited] false])
                (r/dispatch-sync [:state [:fullscreen :cv] false])
                (restore/begin! {})
                (reset! *now 0)
                (reset! *scheduled [])
                (reset! *delivered [])
                (reset! *pending {})
                (await! (support/render! root
                          [:main {:style {:min-height "500vh"}}
                           [:button {:id "destination-origin"} "Focus before the demo"]
                           [cv/<cv>]]))
                (await! (support/wait-for! #(= 4 (count @*scheduled)) "CV did not schedule its native demo"))
                (is (= [2000 3000 4500 5500] @*scheduled) "Preserve the mounted first-visit demo's original timing")
                (let [cv-node (.querySelector element "#cv")]
                  (is (some? cv-node) "The actual CV owner mounted")
                  (when-not mounted?
                    (await! (support/render! root
                              [:main {:style {:min-height "500vh"}}
                               [:button {:id "destination-origin"} "Destination focus"]
                               [:div {:style {:height "200vh"}}]
                               [:button {:id "fullscreen-btn"} "Different destination control"]]))
                    ((:stop (first (:cv pages/controllers))) nil)
                    (await! (support/settle!))
                    (is (false? (.-isConnected cv-node)) "SPA-style replacement unmounted the actual CV")
                    (is (empty? @*pending) "Leaving CV cancels its owned demo timers"))
                  (.scrollTo js/window #js {:top 0, :behavior "instant"})
                  (let [origin (.querySelector element "#destination-origin")
                        focus-target (.querySelector element "#fullscreen-btn")]
                    (.focus origin #js {:preventScroll true})
                    (await! (drive! 2000))
                    (is (= mounted? (boolean (await! (support/state-at! [:state :fullscreen :cv]))))
                        "Only the mounted CV may auto-expand")
                    (await! (drive! 3000))
                    (when mounted?
                      (await! (support/wait-for! #(pos? (.-scrollY js/window)) "Mounted CV did not scroll down")))
                    (let [down (.-scrollY js/window)]
                      (await! (drive! 4500))
                      (when mounted?
                        (await! (support/wait-for! #(< (.-scrollY js/window) down) "Mounted CV did not scroll back")))
                      (await! (drive! 5500))
                      (when (some #(= :focus-element (first %)) @*delivered)
                        (await! (support/wait-for! #(identical? (.querySelector element "#fullscreen-btn")
                                                              (.-activeElement js/document))
                                                  "Native delayed focus did not commit")))
                      (if mounted?
                        (is (identical? focus-target (.-activeElement js/document)) "Mounted demo focuses its native control")
                        (do
                          (is (zero? (.-scrollY js/window)) "Unmounted CV cannot scroll the destination")
                          (is (identical? origin (.-activeElement js/document)) "Unmounted CV cannot focus a destination control"))))))
                (await! (support/render! root nil)))))
          (.catch #(is false (str %)))
          (.finally
            (fn []
              (try
                (when @*root (support/unmount! @*root))
                (finally
                  (reset! *pending {})
                  (set! js/setTimeout original-set-timeout)
                  (set! js/clearTimeout original-clear-timeout)
                  (r/clear-global-interceptor :cv-lifecycle-test/actions)
                  (.remove element)
                  (reset! restore/*context context)
                  (restore-db!)
                  (when (.-isConnected active-element) (.focus active-element #js {:preventScroll true}))
                  (.scrollTo js/window #js {:top scroll-y, :behavior "instant"})
                  (done)))))))))
