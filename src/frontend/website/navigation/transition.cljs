(ns website.navigation.transition
  "Browser lifecycle adapter for CSS page transitions. React owns all page markup."
  (:require [website.react :as r]

            [website.listener :as listener]
            [website.render-context :as context]
            [website.component.restore :as restore]
            [website.component.motion :as motion]))

(defonce *transition (atom nil))
(defonce *generation (atom 0))
(defonce *destination (atom nil))

(r/reg-cofx :page/destination
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
(r/reg-fx :page/replace-destination replace-destination!)
(defonce *restore-cleanup (atom nil))

(defonce *restored-position (atom nil))
(r/reg-cofx :scroll/restoring?
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
  [position & [resolve-position behavior position-animations]]
  (when-let [cleanup! @*restore-cleanup] (cleanup!))
  (set! (.-scrollRestoration js/history) "manual")
  (reset! *restored-position position)
  (let [smooth? (and (= "smooth" behavior) (not (motion/reduced-motion?)))
        *started-position (atom nil)
        *observer (atom nil)
        *mutations (atom nil)
        *timeout (atom nil)
        *frame (atom nil)
        *previous (atom nil)
        *waiting (atom #{})
        *wake (atom nil)
        *unlisten (atom nil)
        *stop-listeners (atom nil)
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
                     (when-let [stop! @*stop-listeners] (stop!))
                     (reset! *restore-cleanup nil)))
        measure! (fn measure! []
                   (reset! *frame nil)
                   (when @*active?
                     ;; Scroll early too: it exposes lazy content at the saved position.
                     (let [requested (if resolve-position (resolve-position) position)]
                       (if (nil? requested)
                         (cleanup!)
                         (let [maximum (max 0 (- (.-scrollHeight (.-documentElement js/document))
                                                (.-innerHeight js/window)))
                               position' (if resolve-position (min maximum (max 0 requested)) requested)
                               _ (do (when-not smooth?
                                       (.scrollTo js/window #js {:top position' :left 0 :behavior "instant"}))
                                     (reset! *restored-position position'))
                               animations (vec (concat (layout-animations)
                                                       (when position-animations (position-animations))))
                               main (.getElementById js/document "main")
                               images (array-seq (.querySelectorAll (or main js/document) "img"))
                               assets-ready? (and (not= "loading" (some-> js/document .-fonts .-status))
                                                  (every? #(or (> (+ (.-top (.getBoundingClientRect %)) (.-scrollY js/window))
                                                                (+ position' (.-innerHeight js/window)))
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
                                         (when main (+ (.-top (.getBoundingClientRect main)) (.-scrollY js/window)))
                                         (when resolve-position position')]
                               layout-ready? (and (empty? animations) (restore/layout-ready?) assets-ready?
                                                  (not (some-> (.getElementById js/document "app")
                                                               (.hasAttribute "data-document-pending"))))
                               ready? (and layout-ready?
                                           (<= (js/Math.abs (- (.-scrollY js/window) position')) 1))]
                           (doseq [animation animations :when (not (contains? @*waiting animation))]
                             (swap! *waiting conj animation)
                             (.then (.-finished animation)
                                    (fn [_] (when @*active? (@*wake)))
                                    (fn [_] (when @*active? (@*wake)))))
                           (if smooth?
                             (if layout-ready?
                               (let [stable? (= geometry @*previous)
                                     started? (= position' @*started-position)]
                                 ;; Wait for intrinsic row growth before starting. A
                                 ;; later geometry change retargets the native scroll.
                                 (when (and stable? (not started?))
                                   (reset! *started-position position')
                                   (.scrollTo js/window #js {:top position' :left 0 :behavior "smooth"}))
                                 (if (and stable? started? ready?)
                                   (cleanup!)
                                   (do (reset! *previous geometry)
                                       (reset! *frame (js/requestAnimationFrame measure!)))))
                               (reset! *previous nil))
                             (if (and ready? (= geometry @*previous))
                               (cleanup!)
                               (do
                                 (reset! *previous (when ready? geometry))
                                 ;; Pending layout wakes through commit, resize,
                                 ;; load or font events rather than frame polling.
                                 (when ready?
                                   (reset! *frame (js/requestAnimationFrame measure!)))))))))))
        schedule! (fn [& _]
                    (when (and @*active? (nil? @*frame))
                      (reset! *previous nil)
                      (reset! *frame (js/requestAnimationFrame measure!))))]
    (reset! *restore-cleanup cleanup!)
    (reset! *wake schedule!)
    (reset! *unlisten (restore/listen-layout! schedule!))
    (reset! *stop-listeners
      (listener/listen! :navigation-restore
        (into [{:target "document", :event "load", :handler schedule!, :capture? true}
               {:target "document", :event "error", :handler schedule!, :capture? true}]
              (map (fn [event]
                     {:target "window", :event event, :handler cleanup!, :passive? true})
                   events))))
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
    (reset! *timeout (js/setTimeout cleanup! 15000))
    (schedule!)))

(defn navigate!
  "Capture the old page, then commit the destination route through ordinary events.
   Initial hydration, query changes, and unsupported browsers need no snapshot."
  [match animate? & [back?]]
  ;; Initial routing must not cancel restoration started before mounting.
  (when (pos? @*generation)
    (when-let [cleanup! @*restore-cleanup] (cleanup!)))
  (when back? (restore/spa-back!))
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
                       (r/dispatch [:common/navigate (:match @*destination)
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
                     (r/dispatch [:page/transition-finished generation]))))
      (update!))))

(r/reg-event-db :page/transition-finished
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

(defn- anchor-animations [element]
  ;; The native document can hold its entrance clock during hydration. Keep the
  ;; initial position until these ancestor transforms finish; client rectangles
  ;; then give the precise final coordinate, including fractional layout units.
  (when (.-getAnimations element)
    (mapcat (fn [node]
              (filter (fn [animation]
                        (let [effect (.-effect animation)]
                          (and effect
                               (not (#{"finished" "idle"} (.-playState animation)))
                               (js/isFinite (.-endTime (.getComputedTiming effect)))
                               (some #(some #{"transform" "translate" "scale" "rotate"}
                                            (array-seq (js/Object.keys %)))
                                     (array-seq (.getKeyframes effect))))))
                      (array-seq (.getAnimations node))))
            (take-while some? (iterate #(.-parentElement %) element)))))

(defn restore-anchor!
  "Follow a committed component while ancestor rows, code or fonts settle."
  [id]
  (let [root (.getElementById js/document "app")
        pending? (and root (.hasAttribute root "data-document-pending")
                      (= id (.getAttribute root "data-document-target")))
        positioned? (and root (= id (.getAttribute root "data-document-positioned")))]
    ;; Native positioning preserves the initial entrance. Hydration must still
    ;; follow later layout changes rather than treating that first offset as final.
    (when (and positioned? (not pending?)) (.removeAttribute root "data-document-positioned"))
    (when (.getElementById js/document id)
      (restore-position! (.-scrollY js/window)
        (fn []
          (when (and root (not (.hasAttribute root "data-document-pending"))
                     (= id (.getAttribute root "data-document-positioned")))
            (.removeAttribute root "data-document-positioned"))
          (when-let [target (.getElementById js/document id)]
            (let [margin (js/parseFloat (.-scrollMarginTop (js/getComputedStyle target)))]
              (- (+ (.-top (.getBoundingClientRect target)) (.-scrollY js/window))
                 (if (js/isNaN margin) 0 margin)))))
        "smooth"
        #(when-let [target (.getElementById js/document id)] (anchor-animations target))))))

(defn use-ready!
  "Complete navigation after React commits the subscribed route and its shell.
   Releasing a height reservation must not replay scrolling or route completion."
  [commit]
  (let [*handled (r/use-ref nil)]
    (r/use-layout-effect
      (fn []
        (let [navigation (when commit (update commit :completion dissoc :outgoing-height))]
          (when (and navigation (not= navigation (.-current *handled)))
            (set! (.-current *handled) navigation)
            (ready! (:target commit) (:completion commit))))
        nil)
      [commit])))

(r/reg-event-fx :page/navigate
  (fn [{:keys [db]} [_ match]]
    (let [history? (or (get-in db [:state :browser-nav :got-nav])
                       (and (nil? (get-in db [:state :scroll :component-focus]))
                            (= 2 (get-in db [:state :browser-nav :nav-type]))))]
    {:db (cond-> db (:path match)
           (assoc-in [:state :scroll :component-focus] {:path (:path match), :scroll? (not history?)}))
     :page/transition
     [match (and @context/*interactive?
                 (some? (:common/route db))
                 (not (and (get-in match [:data :transition-key])
                           (= (get-in match [:data :transition-key])
                              (get-in db [:common/route :data :transition-key]))))
                 (not= (:path match) (get-in db [:common/route :path])))
      (boolean (get-in db [:state :browser-nav :got-nav]))]})))

(r/reg-fx :page/transition (fn [[match animate? back?]] (navigate! match animate? back?)))
(r/reg-event-fx :page/ready
  (fn [{:keys [db]} [_ target complete!]]
    {:db (assoc db :page/commit {:target target :completion complete!})
     :page/flush nil}))

(r/reg-fx :page/flush
  (fn [_]
    ;; Reagent normally batches updates on requestAnimationFrame. A native
    ;; transition can suspend those frames until its update promise resolves.
    ;; Drain Reagent outside React's commit; the layout effect resolves only
    ;; after React has actually committed the destination, never after a fetch.
    (js/queueMicrotask r/flush)))
