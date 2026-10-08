(ns tolgraven.navigation.transition
  "Browser lifecycle adapter for CSS page transitions. React owns all page markup."
  (:require [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.motion :as motion]))

(defonce *transition (atom nil))
(defonce *generation (atom 0))
(defonce *destination (atom nil))

(rf/reg-cofx :page/destination
  (fn [coeffects] (assoc coeffects :page/destination @*destination)))

(defn latest-match
  "Resolve at event handling time: a native callback may have queued a shell
   before the module's replacement event updated the pending destination."
  [match completion destination]
  (if (and (some? (:transition-id completion))
           (= (:transition-id completion) (:generation destination))
           (= (select-keys match [:path :path-params :query-params])
              (select-keys (:match destination) [:path :path-params :query-params])))
    (:match destination)
    match))

(defn replace-destination!
  "Keep a pending native callback current when its module finishes first."
  [match]
  (swap! *destination
    (fn [pending]
      (if (and (= (:generation pending) @*generation)
               (= (select-keys match [:path :path-params :query-params])
                  (select-keys (:match pending) [:path :path-params :query-params])))
        (assoc pending :match match)
        pending))))
(rf/reg-fx :page/replace-destination replace-destination!)
(defonce *restore-cleanup (atom nil))

(defonce *restored-position (atom nil))
(rf/reg-cofx :scroll/restoring?
  (fn [coeffects]
    (assoc coeffects :scroll/restoring?
           (boolean (or @*restore-cleanup
                        (and (exists? js/window) (number? @*restored-position)
                             (<= (js/Math.abs (- (.-scrollY js/window) @*restored-position)) 1)))))))

(def layout-properties
  #{"height" "minHeight" "maxHeight" "width" "minWidth" "maxWidth"
    "marginTop" "marginBottom" "paddingTop" "paddingBottom" "fontSize"
    "lineHeight" "flexBasis" "gridTemplateRows"})

(defn- layout-animations []
  (when (.-getAnimations js/document)
    (filter (fn [animation]
              (when-let [effect (.-effect animation)]
                (and (or (= "running" (.-playState animation)) (.-pending animation))
                     (js/isFinite (.-endTime (.getComputedTiming effect)))
                     (some #(some layout-properties (array-seq (js/Object.keys %)))
                           (array-seq (.getKeyframes effect))))))
            (array-seq (.getAnimations js/document)))))

(defn restore-position!
  "Restore against committed layout, releasing only after mounted code/data,
   layout-affecting images/fonts and two measured frames agree on the target.
   User input/navigation cancels immediately; the timeout only bounds failure."
  [position]
  (when-let [cleanup! @*restore-cleanup] (cleanup!))
  (set! (.-scrollRestoration js/history) "manual")
  (reset! *restored-position position)
  (let [*observer (atom nil)
        *mutations (atom nil)
        *timeout (atom nil)
        *frame (atom nil)
        *previous (atom nil)
        *waiting (atom #{})
        *wake (atom nil)
        *unlisten (atom nil)
        *event-listener (atom nil)
        *active? (atom true)
        events ["wheel" "touchstart" "pointerdown" "keydown" "pagehide"]
        cleanup! (fn cleanup! [& [event]]
                   (when event (reset! *restored-position nil))
                   (when (compare-and-set! *active? true false)
                     (when-let [observer @*observer] (.disconnect observer))
                     (when-let [observer @*mutations] (.disconnect observer))
                     (when @*timeout (js/clearTimeout @*timeout))
                     (when @*frame (js/cancelAnimationFrame @*frame))
                     (when-let [unlisten! @*unlisten] (unlisten!))
                     (.removeEventListener js/document "load" @*event-listener true)
                     (.removeEventListener js/document "error" @*event-listener true)
                     (doseq [event events] (.removeEventListener js/window event cleanup!))
                     (reset! *restore-cleanup nil)))
        measure! (fn measure! []
                   (reset! *frame nil)
                   (when @*active?
                     ;; Scroll early too: it exposes lazy content at the saved position.
                     (.scrollTo js/window #js {:top position :left 0 :behavior "instant"})
                     (let [animations (vec (layout-animations))
                           main (.getElementById js/document "main")
                           images (array-seq (.querySelectorAll (or main js/document) "img"))
                           assets-ready? (and (not= "loading" (some-> js/document .-fonts .-status))
                                              (every? #(or (> (+ (.-top (.getBoundingClientRect %)) (.-scrollY js/window))
                                                            (+ position (.-innerHeight js/window)))
                                                           (.-complete %)
                                                           ;; Explicit dimensions/aspect ratio reserve layout
                                                           ;; without waiting for below-fold lazy downloads.
                                                           (and (pos? (js/parseFloat (.getAttribute % "width")))
                                                                (pos? (js/parseFloat (.getAttribute % "height"))))
                                                           (let [ratio (.-aspectRatio (js/getComputedStyle %))]
                                                             (and (seq ratio) (not (re-find #"auto" ratio)))))
                                                      images))
                           geometry [(.-scrollHeight (.-documentElement js/document))
                                     (.-innerHeight js/window)
                                     (when main (.-height (.getBoundingClientRect main)))
                                     (when main (+ (.-top (.getBoundingClientRect main)) (.-scrollY js/window)))]
                           ready? (and (empty? animations) (restore/layout-ready?) assets-ready?
                                       (<= (js/Math.abs (- (.-scrollY js/window) position)) 1))]
                       (doseq [animation animations :when (not (contains? @*waiting animation))]
                         (swap! *waiting conj animation)
                         (.then (.-finished animation)
                                (fn [_] (when @*active? (@*wake)))
                                (fn [_] (when @*active? (@*wake)))))
                       (if (and ready? (= geometry @*previous))
                         (cleanup!)
                         (do
                           (reset! *previous (when ready? geometry))
                           ;; Only ready layout needs another measured frame. Pending
                           ;; layout wakes through commit, resize, load or font events.
                           (when ready?
                             (reset! *frame (js/requestAnimationFrame measure!))))))))
        schedule! (fn [& _]
                    (when (and @*active? (nil? @*frame))
                      (reset! *previous nil)
                      (reset! *frame (js/requestAnimationFrame measure!))))]
    (reset! *restore-cleanup cleanup!)
    (reset! *wake schedule!)
    (reset! *event-listener schedule!)
    (reset! *unlisten (restore/listen-layout! schedule!))
    (.addEventListener js/document "load" schedule! true)
    (.addEventListener js/document "error" schedule! true)
    (when (exists? js/ResizeObserver)
      (reset! *observer (js/ResizeObserver. schedule!))
      (.observe @*observer (.-body js/document))
      (.observe @*observer (.-documentElement js/document)))
    (when (exists? js/MutationObserver)
      (reset! *mutations (js/MutationObserver. schedule!))
      (.observe @*mutations (.-body js/document)
                #js {:childList true :subtree true :characterData true :attributes true}))
    (when-let [fonts (.-fonts js/document)]
      (.then (.-ready fonts) schedule!))
    (doseq [event events] (.addEventListener js/window event cleanup! #js {:passive true}))
    (reset! *timeout (js/setTimeout cleanup! 15000))
    (schedule!)))

(defn navigate!
  "Capture the old page, then commit the destination route through ordinary events.
   Initial hydration, query changes, and unsupported browsers need no snapshot."
  [match animate? & [back?]]
  ;; Initial routing must not cancel restoration started before mounting.
  (when (pos? @*generation)
    (when-let [cleanup! @*restore-cleanup] (cleanup!)))
  (when back? (restore/begin! {:back? true}))
  (let [generation (swap! *generation inc)
        animate? (and animate? (not back?) (not (motion/reduced-motion?)))
        native? (and animate? (exists? js/document) (fn? (.-startViewTransition js/document)))
        outgoing-top (when (and animate? (exists? js/document))
                       (some-> (.querySelector js/document "#main > .page-root") .getBoundingClientRect .-top))
        outgoing-height (when native?
                          (some-> (.getElementById js/document "main") .getBoundingClientRect .-height))
        update! (fn []
                  (js/Promise.
                   (fn [resolve! _]
                     (if (= generation @*generation)
                       (rf/dispatch [:common/navigate (:match @*destination)
                                     {:current? #(= generation @*generation)
                                      :resolve! resolve!
                                      :transition-id generation
                                      :outgoing-height outgoing-height
                                      :outgoing-top outgoing-top
                                      :fallback? (and animate? (not native?))}])
                       (resolve!)))))]
    (reset! *restored-position nil)
    (reset! *destination {:generation generation :match match})
    (when-let [transition @*transition] (.skipTransition transition))
    (reset! *transition nil)
    (if native?
      (let [transition (.startViewTransition js/document update!)]
        (reset! *transition transition)
        ;; Skipping an obsolete animation rejects ready, but still runs update!.
        (-> (.-ready transition)
            (.then (fn []
                     (when (and (identical? transition @*transition)
                                (.-getAnimations js/document))
                       (motion/prefer-high-frame-rate!
                         (filter #(#{"page-opacity-out" "page-opacity-in-a"} (.-animationName %))
                                 (array-seq (.getAnimations js/document)))))))
            (.catch (fn [_] nil)))
        (.finally (.-finished transition)
                  #(when (identical? transition @*transition)
                     (reset! *transition nil)
                     (rf/dispatch [:page/transition-finished generation]))))
      (update!))))

(rf/reg-event-db :page/transition-finished
  (fn [db [_ generation]]
    (if (= generation (get-in db [:page/commit :completion :transition-id]))
      (update-in db [:page/commit :completion] dissoc :outgoing-height)
      db)))

(defn ready!
  "Position an already committed destination and release its incoming capture.
   Called by the page's layout effect, including when it contains only a shell.
   Never schedule an animation frame inside a native transition's update phase."
  [target completion]
  (let [{:keys [current? resolve!]} completion]
    (when (exists? js/window)
      (when (or (nil? current?) (current?))
        (when target
          (if (number? target)
            (restore-position! target)
            (when-let [element (.getElementById js/document target)]
              (.scrollIntoView element #js {:block "start" :behavior "instant"})
              ;; Anchor positioning is controlled navigation too. Hold adaptive
              ;; header reactions until that layout is settled.
              (restore-position! (.-scrollY js/window))))))
      (when resolve! (resolve!)))))

(defn use-ready!
  "Complete navigation after React commits the subscribed route and its shell.
   Releasing a height reservation must not replay scrolling or route completion."
  [commit]
  (let [*handled (rf/use-ref nil)]
    (rf/use-layout-effect
      (fn []
        (let [navigation (when commit (update commit :completion dissoc :outgoing-height))]
          (when (and navigation (not= navigation (.-current *handled)))
            (set! (.-current *handled) navigation)
            (ready! (:target commit) (:completion commit))))
        js/undefined)
      #js [commit])))

(rf/reg-event-fx :page/navigate
  (fn [{:keys [db]} [_ match]]
    {:page/transition
     [match (and @context/*interactive?
                 (some? (:common/route db))
                 (not (and (get-in match [:data :transition-key])
                           (= (get-in match [:data :transition-key])
                              (get-in db [:common/route :data :transition-key]))))
                 (not= (:path match) (get-in db [:common/route :path])))
      (boolean (get-in db [:state :browser-nav :got-nav]))]}))

(rf/reg-fx :page/transition (fn [[match animate? back?]] (navigate! match animate? back?)))
(rf/reg-event-fx :page/ready
  (fn [{:keys [db]} [_ target complete!]]
    {:db (assoc db :page/commit {:target target :completion complete!})
     :page/flush nil}))

(rf/reg-fx :page/flush
  (fn [_]
    ;; Reagent normally batches updates on requestAnimationFrame. A native
    ;; transition can suspend those frames until its update promise resolves.
    ;; Drain Reagent outside React's commit; the layout effect resolves only
    ;; after React has actually committed the destination, never after a fetch.
    (js/queueMicrotask r/flush)))
