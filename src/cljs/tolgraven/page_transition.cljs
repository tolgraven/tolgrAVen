(ns tolgraven.page-transition
  "Browser lifecycle adapter for CSS page transitions. React owns all page markup."
  (:require [reagent.core :as r]
            [react-dom :as react-dom]
            [tolgraven.react :as rf]
            [tolgraven.render-context :as context]))

(defonce *transition (atom nil))
(defonce *generation (atom 0))

(defn navigate!
  "Capture the old page, then commit the prepared route through ordinary events.
   Initial hydration, query changes, and unsupported browsers need no snapshot."
  [match animate?]
  (let [generation (swap! *generation inc)
        update! (fn []
                  (js/Promise.
                   (fn [resolve! _]
                     (if (= generation @*generation)
                       (rf/dispatch [:common/navigate match
                                     {:current? #(= generation @*generation)
                                      :resolve! resolve!}])
                       (resolve!)))))]
    (when-let [transition @*transition] (.skipTransition transition))
    (reset! *transition nil)
    (if (and animate? (exists? js/document)
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

(defn- visible-images-ready!
  "Decode visible images before releasing the incoming snapshot. Transport and
   fallback errors remain owned by the ordinary image/data adapters."
  []
  (let [main (.getElementById js/document "main")]
    (js/Promise.all
     (into-array
      (for [image (when main (array-seq (.querySelectorAll main "img")))
            :let [rect (.getBoundingClientRect image)]
            :when (and (fn? (.-decode image))
                       (< (.-top rect) (.-innerHeight js/window))
                       (pos? (.-bottom rect)))]
        (.catch (.decode image) (fn [_] nil)))))))

(defn ready!
  "Flush the committed React page and position it before the incoming capture.
   The callback runs after re-frame's queued controller and layout events, rather
   than guessing readiness with navigation timers."
  [target completion]
  (let [{:keys [current? resolve!]} completion]
    (when (exists? js/window)
      (r/after-render
       (fn []
         (if (and current? (not (current?)))
           (when resolve! (resolve!))
           (do
             (react-dom/flushSync r/flush)
             (when target
               (if (number? target)
                 (.scrollTo js/window #js {:top target :left 0 :behavior "instant"})
                 (when-let [element (.getElementById js/document target)]
                   (.scrollIntoView element #js {:block "start" :behavior "instant"}))))
             (when resolve!
               (-> (visible-images-ready!)
                   (.then resolve!)
                   (.catch resolve!))))))))))

(rf/reg-event-fx :page/navigate
  (fn [{:keys [db]} [_ match]]
    {:page/transition
     [match (and @context/*interactive?
                 (some? (:common/route db))
                 (not= (:path match) (get-in db [:common/route :path])))]}))

(rf/reg-fx :page/transition (fn [[match animate?]] (navigate! match animate?)))
(rf/reg-event-fx :page/ready
  (fn [_ [_ target complete!]] {:page/ready [target complete!]}))
(rf/reg-fx :page/ready (fn [[target complete!]] (ready! target complete!)))
