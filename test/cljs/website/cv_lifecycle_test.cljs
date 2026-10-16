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

;; Keep awaited phases separate: one large core.async state machine generates
;; JavaScript too deeply nested for Chrome to evaluate before tests can start.
(defn- cv-fixture []
  {:restore-db! (r/make-restore-fn)
   :context @restore/*context
   :return-marker (.getAttribute (.-documentElement js/document) "data-document-return")
   :scroll-y (.-scrollY js/window)
   :active-element (.-activeElement js/document)
   :original-set-timeout js/setTimeout
   :schedule! (r/get-handler :fx :dispatch-later)
   :element (.createElement js/document "div")
   :*root (atom nil)
   :*scheduling? (atom false)
   :*next-id (atom 0)
   :*pending (atom {})
   :*scheduled (atom [])
   :*timer-events (atom [])
   :*delivered (atom [])
   :*claims (atom [])
   :*restorations (atom [])
   :*hold-restore? (atom false)
   :*now (atom 0)})

(defn- install-cv-clock!
  [{:keys [original-set-timeout schedule! *scheduling? *next-id *pending *scheduled
           *timer-events *delivered *claims *restorations *hold-restore? *now]}]
  ;; Only the registered effect's native clock changes. The actual dispatch-later
  ;; callbacks, CV events, DOM and scroll/focus effects still run.
  (set! js/setTimeout
    (fn [callback milliseconds & args]
      (if (and @*scheduling? (#{2000 3000 4500 5500} milliseconds))
        (let [id (swap! *next-id dec)]
          (swap! *scheduled conj milliseconds)
          (swap! *pending assoc id {:at (+ @*now milliseconds)
                                    :callback #(.apply callback nil (to-array args))})
          id)
        (.apply original-set-timeout js/window (to-array (into [callback milliseconds] args))))))
  (r/reg-fx :dispatch-later
    (fn [arguments]
      (let [timers (if (map? arguments) [arguments] arguments)]
        (swap! *timer-events into (map :dispatch timers))
        (reset! *scheduling? true)
        (try (schedule! arguments) (finally (reset! *scheduling? false))))))
  ;; cv-visit-test covers actual disk/legacy migration. Control only its result
  ;; effect here, including late results after the owning component departs.
  (r/reg-fx :cv/restore-visit
    (fn [generation]
      (swap! *restorations conj generation)
      (when-not @*hold-restore?
        (r/dispatch [:cv/visit-restored generation false]))))
  (r/reg-cofx :ls #(assoc % :ls {}))
  (r/reg-fx :ls #(swap! *claims conj %))
  (r/reg-global-interceptor
    (r/->interceptor
      :id :cv-lifecycle-test/actions
      :after (fn [context]
               (let [event (get-in context [:coeffects :event])]
                 (when (#{:cv/fullscreen :cv/demo} (first event))
                   (swap! *delivered conj event)))
               context))))

(defn- drive-cv-clock! [{:keys [*pending *now *delivered]} time]
  (go-promise
    (let [jobs (sort-by (comp :at val) (filter #(<= (:at (val %)) time) @*pending))
          delivered (count @*delivered)]
      (reset! *now time)
      (doseq [[id {:keys [callback]}] jobs]
        (swap! *pending dissoc id)
        (callback))
      ;; Inactive generations still process these events but produce no effects.
      (when (seq jobs)
        (await! (support/wait-for! #(>= (count @*delivered) (+ delivered (count jobs)))
                                  "CV timer callbacks did not reach their real events")))
      (await! (support/settle!)))))

(defn- cv-mode [mode]
  (let [late? (boolean (#{:late-unmounted :late-pagehide} mode))]
    {:mode mode
     :unmounted? (boolean (#{:unmounted :late-unmounted} mode))
     :pagehide? (boolean (#{:pagehide :late-pagehide} mode))
     :returned? (boolean (#{:network-return :paired-return :spa-from-returned} mode))
     :active? (boolean (#{:mounted :spa-from-returned} mode))
     :late? late?
     :expected-times (cond
                       (#{:network-return :paired-return} mode) []
                       (or late? (= mode :early-pagehide)) [2000]
                       :else [2000 3000 4500 5500])}))

(defn- prepare-cv-mode!
  [{:keys [*hold-restore? *now *scheduled *timer-events *delivered *claims *restorations *pending]}
   {:keys [mode returned? late?]} seed]
  (r/dispatch-sync [:editor-visual/install seed false])
  (r/dispatch-sync [:state [:cv :visited] false])
  (r/dispatch-sync [:state [:fullscreen :cv] false])
  (restore/begin! (case mode
                    (:mounted :network-return :spa-from-returned) {:hydrate? true}
                    :paired-return {:hydrate? true, :back? true}
                    {}))
  (when (#{:network-return :spa-from-returned} mode) (restore/hydrated!))
  (when (= mode :spa-from-returned) (restore/navigate! "/cv"))
  (if returned?
    (.setAttribute (.-documentElement js/document) "data-document-return" "true")
    (.removeAttribute (.-documentElement js/document) "data-document-return"))
  (reset! *hold-restore? late?)
  (reset! *now 0)
  (reset! *scheduled [])
  (reset! *timer-events [])
  (reset! *delivered [])
  (reset! *claims [])
  (reset! *restorations [])
  (reset! *pending {}))

(defn- mount-cv-mode!
  [{:keys [*root element *scheduled *timer-events *restorations *claims]}
   {:keys [mode late? expected-times]}]
  (go-promise
    (await! (support/render! @*root
              [:main {:style {:min-height "500vh"}
                      :ref (when (= mode :early-pagehide)
                             (fn [node]
                               (when node
                                 (is (empty? @*scheduled) "Pagehide can precede queued timer setup")
                                 (.dispatchEvent js/window
                                   (js/PageTransitionEvent. "pagehide" #js {:persisted true})))))}
               [:button {:id "destination-origin"} "Focus before the demo"]
               [cv/<cv>]]))
    (await! (support/wait-for! #(= 1 (count @*restorations))
                              "The mounted CV did not acquire its visit result"))
    (when-not (or late? (= mode :early-pagehide))
      (await! (support/wait-for! #(seq @*claims) "The native visit claim did not commit")))
    (await! (support/settle!))
    (is (= expected-times @*scheduled)
        (str "Preserve first-visit deadlines and suppress retired/returned generations: " mode))
    (let [cv-node (.querySelector element "#cv")
          demo (await! (support/state-at! [:state :cv :demo]))]
      (is (some? cv-node) "The actual CV owner mounted")
      (when (#{:network-return :paired-return} mode)
        (is (true? (await! (support/state-at! [:state :cv :visited])))
            "A settled return still claims its durable visit")
        (is (= [{:cv-visited true}] @*claims) "Return suppression does not discard the native durable claim"))
      {:cv-node cv-node, :demo demo, :old-events @*timer-events})))

(defn- retire-cv-mode!
  [{:keys [*root *restorations *scheduled *claims]}
   {:keys [mode unmounted? pagehide? late?]}
   {:keys [cv-node demo]}]
  (go-promise
    (when unmounted?
      (await! (support/render! @*root
                [:main {:style {:min-height "500vh"}}
                 [:button {:id "destination-origin"} "Destination focus"]
                 [:div {:style {:height "200vh"}}]
                 [:button {:id "fullscreen-btn"} "Different destination control"]]))
      ((:stop (first (:cv pages/controllers))) nil)
      (await! (support/settle!))
      (is (false? (.-isConnected cv-node)) "SPA-style replacement unmounted the actual CV"))
    (when pagehide?
      (.dispatchEvent js/window (js/PageTransitionEvent. "pagehide" #js {:persisted true}))
      (await! (support/settle!)))
    (when (or pagehide? (= mode :early-pagehide))
      (is (.-isConnected cv-node) "A retained document keeps its mounted CV"))
    (when late?
      (is (= [(:generation demo)] @*restorations) "The real visit acquisition was held")
      (r/dispatch-sync [:cv/visit-restored (:generation demo) false])
      (await! (support/settle!))
      (is (= [2000] @*scheduled) "A late storage result cannot schedule a departed generation")
      (is (empty? @*claims) "A late result cannot claim a departed visit"))))

(defn- prepare-cv-observation! [{:keys [element]}]
  (go-promise
    ;; Finish font/reporter reflow before measuring the shared viewport.
    (await! (.-ready (.-fonts js/document)))
    (await! (support/settle!))
    (.scrollTo js/window #js {:top 0, :behavior "instant"})
    (let [origin (.querySelector element "#destination-origin")
          focus-target (.querySelector element "#fullscreen-btn")]
      (.focus origin #js {:preventScroll true})
      (await! (support/settle!))
      {:origin origin, :focus-target focus-target, :initial-scroll (.-scrollY js/window)})))

(defn- observe-cv-down! [fixture {:keys [active?]}]
  (go-promise
    (await! (drive-cv-clock! fixture 2000))
    (let [fullscreen? (boolean (await! (support/state-at! [:state :fullscreen :cv])))
          before-down (.-scrollY js/window)]
      (await! (drive-cv-clock! fixture 3000))
      (when active?
        (await! (support/wait-for! #(> (.-scrollY js/window) before-down)
                                  "Mounted CV did not scroll down")))
      (let [down (.-scrollY js/window)
            saved-origin (await! (support/state-at! [:state :cv :demo :origin]))]
        {:fullscreen? fullscreen?, :before-down before-down, :down down, :saved-origin saved-origin}))))

(defn- observe-cv-return! [fixture {:keys [active?]} {:keys [focus-target]} {:keys [before-down]}]
  (go-promise
    (await! (drive-cv-clock! fixture 4500))
    (when active?
      (await! (support/wait-for! #(= before-down (.-scrollY js/window))
                                "Mounted CV did not return to its exact origin")))
    (await! (drive-cv-clock! fixture 5500))
    (when active?
      (await! (support/wait-for! #(identical? focus-target (.-activeElement js/document))
                                "Native delayed focus did not commit")))
    {:final-scroll (.-scrollY js/window), :final-focus (.-activeElement js/document)}))

(defn- assert-cv-outcome!
  [{:keys [mode active?]} {:keys [origin focus-target initial-scroll]}
   {:keys [fullscreen? before-down down saved-origin]} {:keys [final-scroll final-focus]}]
  ;; All native outcomes are collected before these reports can reflow the page.
  (is (= active? fullscreen?) "Only an active ordinary CV demo may auto-expand")
  (if active?
    (do
      (is (> down before-down) "Mounted demo really scrolls down")
      (is (= before-down saved-origin final-scroll)
          "Back and preventScroll focus preserve the absolute pre-demo origin")
      (is (identical? focus-target final-focus) "Mounted demo focuses its native control"))
    (do
      (is (= initial-scroll final-scroll) (str "Inactive CV demo cannot move viewport: " mode))
      (is (identical? origin final-focus) "Inactive CV demo cannot move focus"))))

(defn- prepare-cv-remount! [{:keys [*root element]}]
  (go-promise
    (await! (support/render! @*root nil))
    (await! (support/render! @*root [:main {:style {:min-height "500vh"}}
                                   [:button {:id "destination-origin"} "New owner focus"]
                                   [cv/<cv>]]))
    (let [current (await! (support/state-at! [:state :cv :demo]))
          origin (.querySelector element "#destination-origin")]
      (r/dispatch-sync [:state [:fullscreen :cv] false])
      (.focus origin #js {:preventScroll true})
      (await! (support/settle!))
      {:current current, :origin origin, :baseline (.-scrollY js/window)})))

(defn- check-cv-stale-owner! [{:keys [demo old-events]} {:keys [current origin baseline]}]
  (go-promise
    (r/dispatch-sync [:cv/unmount (:owner demo)])
    (doseq [event old-events] (r/dispatch-sync event))
    (await! (support/settle!))
    (let [retained (await! (support/state-at! [:state :cv :demo]))
          fullscreen? (await! (support/state-at! [:state :fullscreen :cv]))
          position (.-scrollY js/window)
          focus (.-activeElement js/document)]
      (is (and (some? (:owner demo)) (not= (:owner demo) (:owner current)))
          "Remount acquires a distinct actual feature owner")
      (is (> (:generation current) (:generation demo)) "Remount advances its timer generation")
      (is (and (:active? retained) (= (:owner current) (:owner retained)))
          "Stale owned cleanup cannot retire the newly mounted CV")
      (is (and (false? fullscreen?) (= baseline position) (identical? origin focus))
          "Old generation callbacks cannot scroll, expand or focus the new owner"))))

(defn- check-cv-mode! [{:keys [*root] :as fixture} seed mode]
  (go-promise
    (let [options (cv-mode mode)]
      (prepare-cv-mode! fixture options seed)
      (let [mounted (await! (mount-cv-mode! fixture options))]
        (await! (retire-cv-mode! fixture options mounted))
        (let [start (await! (prepare-cv-observation! fixture))
              down (await! (observe-cv-down! fixture options))
              returned (await! (observe-cv-return! fixture options start down))]
          (assert-cv-outcome! options start down returned))
        (when (= mode :mounted)
          (let [remounted (await! (prepare-cv-remount! fixture))]
            (await! (check-cv-stale-owner! mounted remounted))))
        (await! (support/render! @*root nil))))))

(defn- check-cv-queued-retirement! [{:keys [*root element *delivered] :as fixture} seed]
  (go-promise
    (let [options (cv-mode :mounted)]
      (prepare-cv-mode! fixture options seed)
      (let [{:keys [cv-node demo]} (await! (mount-cv-mode! fixture options))
            scroll! (r/get-handler :fx :scroll/by)
            *scroll-effects (atom [])]
        (await! (prepare-cv-observation! fixture))
        ;; Record the actual effect and still execute it. This catches a retired
        ;; source event even when native smooth scrolling has not painted yet.
        (r/reg-fx :scroll/by
          (fn [arguments]
            (swap! *scroll-effects conj {:arguments arguments
                                        :cv-connected? (.-isConnected cv-node)})
            (scroll! arguments)))
        (try
          (let [delivered (count @*delivered)]
            (r/dispatch [:cv/demo (:generation demo) :down])
            (r/dispatch [:cv/fullscreen (:generation demo)])
            ;; React unmount is synchronous; leave the source event queue intact.
            (support/unmount! @*root)
            (reset! *root nil)
            (let [detached? (not (.-isConnected cv-node))
                  destination (await! (support/create-root! element))]
              (reset! *root destination)
              (await! (support/render! destination
                        [:main {:style {:min-height "500vh"}}
                         [:button {:id "fullscreen-btn"} "Different destination control"]]))
              (await! (support/wait-for! #(>= (count @*delivered) (+ delivered 2))
                                        "Queued CV events did not reach their registered handlers"))
              (await! (support/settle!))
              (let [fullscreen? (await! (support/state-at! [:state :fullscreen :cv]))
                    effects @*scroll-effects]
                (is detached? "Queued callback race actually unmounted the CV before draining events")
                (is (false? fullscreen?) "A queued fullscreen event cannot expand after owner disposal")
                (is (empty? effects)
                    (str "A queued demo event cannot issue a scroll effect after owner disposal: " effects)))))
          (finally (r/reg-fx :scroll/by scroll!)))))))

(defn- dispose-cv-fixture!
  [{:keys [*root *pending element original-set-timeout context return-marker
           restore-db! active-element scroll-y]}]
  (try
    (when @*root (support/unmount! @*root))
    (finally
      (reset! *pending {})
      (set! js/setTimeout original-set-timeout)
      (r/clear-global-interceptor :cv-lifecycle-test/actions)
      (.remove element)
      (reset! restore/*context context)
      (if (nil? return-marker)
        (.removeAttribute (.-documentElement js/document) "data-document-return")
        (.setAttribute (.-documentElement js/document) "data-document-return" return-marker))
      (restore-db!)
      (when (.-isConnected active-element) (.focus active-element #js {:preventScroll true}))
      (.scrollTo js/window #js {:top scroll-y, :behavior "instant"}))))

(deftest cv-demo-keeps-its-mounted-timing-and-releases-actions-on-unmount
  (async done
    (let [{:keys [element *root] :as fixture} (cv-fixture)]
      (-> (go-promise
            (install-cv-clock! fixture)
            (.appendChild (.-body js/document) element)
            (let [response (await! (js/fetch "/fixtures/editor-seed.json"))
                  _ (when-not (.-ok response) (throw (js/Error. "Missing local CV test seed")))
                  seed (js->clj (await! (.json response)) :keywordize-keys true)
                  root (await! (support/create-root! element))]
              (reset! *root root)
              (doseq [mode [:mounted :unmounted :pagehide :early-pagehide :network-return
                            :paired-return :spa-from-returned :late-unmounted :late-pagehide]]
                (await! (check-cv-mode! fixture seed mode)))
              (await! (check-cv-queued-retirement! fixture seed))))
          (.catch #(is false (str %)))
          (.finally #(try (dispose-cv-fixture! fixture) (finally (done))))))))

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
