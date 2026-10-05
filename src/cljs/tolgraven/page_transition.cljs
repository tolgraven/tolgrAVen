(ns tolgraven.page-transition
  "Browser lifecycle adapter for CSS page transitions. React owns all page markup."
  (:require [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            [tolgraven.component.restore :as restore]))

(defonce *transition (atom nil))
(defonce *generation (atom 0))
(defonce *destination (atom nil))

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

(defn restore-position!
  "Restore cached history before paint, retaining the target if the first commit
   is shorter than it. Retain it through late layout changes until settled;
   user input or navigation cancels restoration immediately."
  [position]
  (when-let [cleanup! @*restore-cleanup] (cleanup!))
  (set! (.-scrollRestoration js/history) "manual")
  (let [*observer (atom nil)
        *mutations (atom nil)
        *timeout (atom nil)
        *settle-timeout (atom nil)
        *load-listener (atom nil)
        cleanup! (fn cleanup! []
                   (when-let [observer @*observer] (.disconnect observer))
                   (when-let [observer @*mutations] (.disconnect observer))
                   (when @*timeout (js/clearTimeout @*timeout))
                   (when @*settle-timeout (js/clearTimeout @*settle-timeout))
                   (when-let [listener @*load-listener]
                     (.removeEventListener js/document "load" listener true))
                   (doseq [event ["wheel" "touchstart" "pointerdown" "keydown" "pagehide"]]
                     (.removeEventListener js/window event cleanup!))
                   (reset! *restore-cleanup nil))
        restore! (fn []
                   (when @*settle-timeout (js/clearTimeout @*settle-timeout))
                   (.scrollTo js/window #js {:top position :left 0 :behavior "instant"})
                   (when (<= (js/Math.abs (- (.-scrollY js/window) position)) 1)
                     ;; A matching offset can precede image decode or another
                     ;; React commit. Require a quiet layout, not one successful
                     ;; scroll, before releasing the observer.
                     (reset! *settle-timeout
                             (js/setTimeout
                               (fn []
                                 (when-not (or (= "loading" (some-> js/document .-fonts .-status))
                                               (some #(and (not (.-complete %))
                                                           (not= "lazy" (.-loading %)))
                                                     (array-seq (.-images js/document))))
                                   (cleanup!)))
                               500))))]
    (reset! *restore-cleanup cleanup!)
    (reset! *load-listener restore!)
    (.addEventListener js/document "load" restore! true)
    (when (exists? js/ResizeObserver)
      (reset! *observer (js/ResizeObserver. restore!))
      (.observe @*observer (.-body js/document))
      (.observe @*observer (.-documentElement js/document)))
    (when (exists? js/MutationObserver)
      (reset! *mutations (js/MutationObserver. restore!))
      (.observe @*mutations (.-body js/document)
                #js {:childList true :subtree true :characterData true}))
    (doseq [event ["wheel" "touchstart" "pointerdown" "keydown" "pagehide"]]
      (.addEventListener js/window event cleanup! #js {:passive true}))
    (reset! *timeout (js/setTimeout cleanup! 5000))
    (restore!)))

(defn navigate!
  "Capture the old page, then commit the destination route through ordinary events.
   Initial hydration, query changes, and unsupported browsers need no snapshot."
  [match animate? & [back?]]
  ;; Initial routing must not cancel restoration started before mounting.
  (when (pos? @*generation)
    (when-let [cleanup! @*restore-cleanup] (cleanup!)))
  (when back? (restore/begin! {:back? true}))
  (let [generation (swap! *generation inc)
        update! (fn []
                  (js/Promise.
                   (fn [resolve! _]
                     (if (= generation @*generation)
                       (rf/dispatch [:common/navigate (:match @*destination)
                                     {:current? #(= generation @*generation)
                                      :resolve! resolve!
                                      :transition-id generation
                                      :fallback? (and animate? (not back?)
                                                      (not (fn? (.-startViewTransition js/document)))
                                                      (not (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)"))))}])
                       (resolve!)))))]
    (reset! *destination {:generation generation :match match})
    (when-let [transition @*transition] (.skipTransition transition))
    (reset! *transition nil)
    (if (and animate? (not back?) (exists? js/document)
             (fn? (.-startViewTransition js/document))
             (not (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)"))))
      (let [transition (.startViewTransition js/document update!)]
        (reset! *transition transition)
        ;; Skipping an obsolete animation rejects ready, but still runs update!.
        (.catch (.-ready transition) (fn [_] nil))
        (.finally (.-finished transition)
                  #(when (identical? transition @*transition)
                     (reset! *transition nil))))
      (update!))))

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
              (.scrollIntoView element #js {:block "start" :behavior "instant"})))))
      (when resolve! (resolve!)))))

(defn use-ready!
  "Complete navigation after React commits the subscribed route and its shell."
  [commit]
  (rf/use-layout-effect
    (fn []
      (when commit (ready! (:target commit) (:completion commit)))
      js/undefined)
    #js [commit]))

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
