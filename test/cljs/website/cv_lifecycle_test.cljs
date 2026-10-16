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
          return-marker (.getAttribute (.-documentElement js/document) "data-document-return")
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
              (doseq [mode [:mounted :unmounted :pagehide :early-pagehide :network-return :paired-return :spa-from-returned]
                      :let [mounted? (not= mode :unmounted)
                            returned? (#{:network-return :paired-return :spa-from-returned} mode)
                            active? (boolean (#{:mounted :spa-from-returned} mode))
                            expected-times (if (#{:network-return :paired-return} mode)
                                             [] [2000 3000 4500 5500])]]
                (r/dispatch-sync [:editor-visual/install seed false])
                (r/dispatch-sync [:state [:cv :visited] false])
                (r/dispatch-sync [:state [:fullscreen :cv] false])
                (restore/begin! (case mode
                                  :mounted {:hydrate? true}
                                  :network-return {:hydrate? true}
                                  :paired-return {:hydrate? true, :back? true}
                                  :spa-from-returned {:hydrate? true}
                                  {}))
                (when (#{:network-return :spa-from-returned} mode) (restore/hydrated!))
                (when (= mode :spa-from-returned) (restore/navigate! "/cv"))
                (if returned?
                  (.setAttribute (.-documentElement js/document) "data-document-return" "true")
                  (.removeAttribute (.-documentElement js/document) "data-document-return"))
                (reset! *now 0)
                (reset! *scheduled [])
                (reset! *delivered [])
                (reset! *pending {})
                (await! (support/render! root
                          [:main {:style {:min-height "500vh"}
                                  :ref (when (= mode :early-pagehide)
                                         (fn [element]
                                           (when element
                                             (is (empty? @*scheduled) "Pagehide can precede queued timer setup")
                                             (.dispatchEvent js/window
                                               (js/PageTransitionEvent. "pagehide" #js {:persisted true})))))}
                           [:button {:id "destination-origin"} "Focus before the demo"]
                           [cv/<cv>]]))
                (await! (support/settle!))
                (when (seq expected-times)
                  (await! (support/wait-for! #(= 4 (count @*scheduled)) "CV did not schedule its native demo")))
                (is (= expected-times @*scheduled)
                    (str "Preserve ordinary first-visit timing and suppress only the initial return: " mode))
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
                  (when (= mode :pagehide)
                    (.dispatchEvent js/window (js/PageTransitionEvent. "pagehide" #js {:persisted true}))
                    (await! (support/settle!)))
                  (when (#{:pagehide :early-pagehide} mode)
                    (is (.-isConnected cv-node) "A retained document keeps its mounted CV")
                    (is (empty? @*pending) "Pagehide cancels the CV demo before a retained document freezes"))
                  ;; Let fonts and the preceding assertions paint before using
                  ;; the shared window; reporter reflow is not a CV timer action.
                  (await! (.-ready (.-fonts js/document)))
                  (await! (support/settle!))
                  (.scrollTo js/window #js {:top 0, :behavior "instant"})
                  (let [origin (.querySelector element "#destination-origin")
                        focus-target (.querySelector element "#fullscreen-btn")]
                    (.focus origin #js {:preventScroll true})
                    (await! (support/settle!))
                    (let [initial-scroll (.-scrollY js/window)
                          initial-root-top (.-offsetTop element)]
                      (await! (drive! 2000))
                      (let [fullscreen? (boolean (await! (support/state-at! [:state :fullscreen :cv])))]
                        (await! (drive! 3000))
                        (when active?
                          (await! (support/wait-for! #(pos? (.-scrollY js/window)) "Mounted CV did not scroll down")))
                        (let [down (.-scrollY js/window)]
                          (await! (drive! 4500))
                          (when active?
                            (await! (support/wait-for! #(< (.-scrollY js/window) down) "Mounted CV did not scroll back")))
                          (await! (drive! 5500))
                          (when (some #(= :focus-element (first %)) @*delivered)
                            (await! (support/wait-for! #(identical? (.querySelector element "#fullscreen-btn")
                                                                  (.-activeElement js/document))
                                                      "Native delayed focus did not commit")))
                          ;; Collect the whole outcome before assertions can reflow
                          ;; the test reporter and alter native scroll anchoring.
                          (let [final-scroll (.-scrollY js/window)
                                final-focus (.-activeElement js/document)]
                            (is (= active? fullscreen?) "Only an active ordinary CV demo may auto-expand")
                            (if active?
                              (is (identical? focus-target final-focus) "Mounted demo focuses its native control")
                              (do
                                (is (= initial-scroll final-scroll)
                                    (str "Inactive CV demos cannot move the viewport: " mode
                                         {:initial-scroll initial-scroll
                                          :final-scroll final-scroll
                                          :initial-root-top initial-root-top
                                          :final-root-top (.-offsetTop element)
                                          :delivered @*delivered}))
                                (is (identical? origin final-focus) "Inactive CV demos cannot move focus")))))))))
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
                  (if (nil? return-marker)
                    (.removeAttribute (.-documentElement js/document) "data-document-return")
                    (.setAttribute (.-documentElement js/document) "data-document-return" return-marker))
                  (restore-db!)
                  (when (.-isConnected active-element) (.focus active-element #js {:preventScroll true}))
                  (.scrollTo js/window #js {:top scroll-y, :behavior "instant"})
                  (done)))))))))

(defn- event-effects [id db event]
  ;; Invoke the actual registered handler without installing native timers.
  (let [handler (:before (last (r/get-handler :event id)))]
    (:effects (handler {:coeffects {:db db, :event event}}))))

(deftest dispatch-in-cancellation-releases-only-the-requested-owner-and-timers
  (let [first-timer {:js-id 101}
        second-timer {:js-id 102}
        other {:js-id 201}
        db {:cv-demo {1 first-timer, 2 second-timer}, :other {1 other}}
        cancel #(event-effects :dispatch-in/cancel db %)
        all (cancel [:dispatch-in/cancel :cv-demo])
        single (cancel [:dispatch-in/cancel :cv-demo 1])
        only (event-effects :dispatch-in/cancel {:cv-demo {1 first-timer}, :other {1 other}}
                            [:dispatch-in/cancel :cv-demo])]
    (is (= {:other {1 other}} (:db all)))
    (is (= #{[:dispatch-in/stop [:cv-demo 1 101]] [:dispatch-in/stop [:cv-demo 2 102]]}
           (set (:fx all))))
    (is (= {:cv-demo {2 second-timer}, :other {1 other}} (:db single)))
    (is (= [[:dispatch-in/stop [:cv-demo 1 101]]] (:fx single)))
    (is (= {:other {1 other}} (:db only)))
    (is (= [[:dispatch-in/stop [:cv-demo 1 101]]] (:fx only)))
    (is (empty? (cancel [:dispatch-in/cancel :missing])))
    (is (empty? (cancel [:dispatch-in/cancel :cv-demo 999])))))

(deftest cancelled-timer-acknowledgement-stops-the-native-handle-without-resurrecting-state
  (let [pending {:state {:dispatch-in {:cv-demo {1 {:ms 2000, :js-id nil}}}}}
        acknowledged (event-effects :dispatch-in/save-timeout-id pending
                                    [:dispatch-in/save-timeout-id :cv-demo 1 101])
        cancelled (event-effects :dispatch-in/save-timeout-id {:state {:dispatch-in {}}}
                                 [:dispatch-in/save-timeout-id :cv-demo 1 101])]
    (is (= 101 (get-in acknowledged [:db :state :dispatch-in :cv-demo 1 :js-id])))
    (is (= {:dispatch-in/stop [:cv-demo 1 101]} cancelled))))
