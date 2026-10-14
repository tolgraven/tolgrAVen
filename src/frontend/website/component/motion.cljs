(ns website.component.motion
  "Root-merged motion and parent-owned exit retention. No wrapper components."
  (:require [clojure.string :as string]
            [website.react :as r]

            [website.component.restore :as restore]))

(r/reg-event-db :component-motion/seen
  (fn [db [_ key]] (assoc-in db [:state :motion-seen key] true)))

;; Internal argument, never confused with a caller's spec or domain-data map.
(defrecord Presence [phase token finish!])
(defn presence? [value] (instance? Presence value))
(def present (->Presence :present nil nil))

(defn dom-root? [form]
  (and (vector? form)
       (or (string? (first form))
           (and (keyword? (first form))
                (not (#{:<> :> :r> :f>} (first form)))))))

(defn- attrs [form] (if (map? (second form)) (second form) {}))
(defn- children [form] (if (map? (second form)) (nnext form) (next form)))
(defn- with-attrs [form attributes]
  (with-meta (into [(first form) attributes] (children form)) (meta form)))
(defn- config [value]
  (cond (string? value) {:class value}
        (keyword? value) {:class (name value)}
        (map? value) value
        :else {}))
(defn- class-text [value]
  (cond (sequential? value) (string/join " " (map class-text value))
        (keyword? value) (name value)
        (nil? value) ""
        :else (str value)))
(defn- set-ref! [reference element]
  (cond (fn? reference) (reference element)
        reference (set! (.-current ^js reference) element)))

(defn reduced-motion? []
  (and (exists? js/window) (.-matchMedia js/window)
       (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)"))))

(defn prefer-high-frame-rate!
  "Request the display's highest rate for native Animation objects when exposed.
   This experimental browser hint never changes playback speed or CSS timing."
  [animations]
  (when-not (reduced-motion?)
    (doseq [animation animations]
      ;; Check inherited API support without reading a getter or creating an
      ;; inert expando in browsers without this experimental property.
      (when (js/Reflect.has animation "frameRate")
        (try
          (set! (.-frameRate animation) "highest")
          ;; An optional optimization must not interrupt navigation or cleanup.
          (catch :default _ nil))))))

(defn next-frame!
  "Start after the initial styles have painted; return cancellation cleanup."
  [callback]
  ;; Two frames ensure the browser paints the initial state before entering.
  (let [*frame (atom nil)]
    (reset! *frame (js/requestAnimationFrame
                   (fn [_] (reset! *frame (js/requestAnimationFrame (fn [_] (callback)))))))
    #(when @*frame (js/cancelAnimationFrame @*frame))))

(defn- seconds [text]
  (let [n (js/parseFloat text)]
    (if (js/isNaN n) 0 (* n (if (string/ends-with? (string/trim text) "ms") 1 1000)))))
(defn- css-duration [durations delays]
  (let [durations (mapv seconds (string/split durations #","))
        delays (mapv seconds (string/split delays #","))]
    (reduce max 0 (map-indexed (fn [i duration]
                                (+ duration (nth delays (mod i (count delays))))) durations))))

(defn finish-animation!
  "Wait for root animations/transitions, bounded even for paused/infinite CSS.
   Cleanup cancels completion, not the browser's styles, so re-entry can reverse."
  [element options finish!]
  (let [*active? (atom true) *timer (atom nil)
        done! #(when (compare-and-set! *active? true false)
                 (when @*timer (js/clearTimeout @*timer))
                 (finish!))
        frame (js/requestAnimationFrame
               (fn [_]
                 (when @*active?
                   (let [style (js/getComputedStyle element)
                         duration (max (css-duration (.-transitionDuration style) (.-transitionDelay style))
                                       (css-duration (.-animationDuration style) (.-animationDelay style)))
                         animations (when (.-getAnimations element) (array-seq (.getAnimations element)))
                         ;; Computed animation timing includes iterations/delays;
                         ;; CSS duration alone can cut finite keyframes short.
                         duration (reduce max duration
                                          (keep (fn [animation]
                                                  (when-let [effect (.-effect animation)]
                                                    (let [end (.-endTime (.getComputedTiming effect))]
                                                      (when (js/isFinite end) end)))) animations))
                         timeout (or (:timeout-ms options) (max 50 (+ duration 100)))]
                     (reset! *timer (js/setTimeout done! (min 10000 timeout)))
                     (if (seq animations)
                       (-> (js/Promise.allSettled (into-array (map #(.-finished %) animations)))
                           (.then (fn [_] (done!))))
                       (when (or (.-getAnimations element) (zero? duration)) (done!)))))))]
    #(do (reset! *active? false)
         (js/cancelAnimationFrame frame)
         (when @*timer (js/clearTimeout @*timer)))))

(defn- skip-enter? [options]
  (let [appearance (config (or (:seen options) (:appear options)))]
    (boolean (and (not= false (:restore? appearance))
                  (or (:hydrating? options) (:server? options)
                      (:remembered? options) (restore/skip-enter?))))))

(defn- stop! [state id]
  (when-let [cleanup! (get @(:stops state) id)]
    (swap! (:stops state) dissoc id)
    (cleanup!)))

(defn- stop-all! [state]
  (reset! (:active? state) false)
  (doseq [id (keys @(:stops state))] (stop! state id)))

(defn- in-viewport? [element]
  (let [rect (.getBoundingClientRect element)]
    (and (pos? (.-bottom rect)) (pos? (.-right rect))
         (< (.-top rect) (.-innerHeight js/window))
         (< (.-left rect) (.-innerWidth js/window)))))

(defn- ensure-media! [state options]
  ;; Exiting children still acknowledge completion after an instance disables
  ;; motion. That completion alone must not acquire a media query/listener.
  (let [enabled? (some options [:appear :seen :exit])]
    (when-not enabled? (stop! state :media))
    (when (and enabled? (not (get @(:stops state) :media))
               (exists? js/window) (.-matchMedia js/window))
      (let [query (.matchMedia js/window "(prefers-reduced-motion: reduce)")
            change! #(reset! (:reduced? state) (.-matches query))]
        ;; This feature is required by validation schemas. The application
        ;; listener registry depends on validation and would create a cycle.
        (.addEventListener query "change" change!)
        (swap! (:stops state) assoc :media
               #(.removeEventListener query "change" change!))))))

(defn- commit-motion! [state element options]
  (ensure-media! state options)
  (when (and element (:appear options) (nil? @(:viewport-enter? state)))
    ;; Preserve first-screen document timing and settled local/history returns.
    ;; A new offscreen entrance uses the existing observer path, once visible.
    (let [appearance (config (:appear options))
          offscreen? (not (in-viewport? element))
          settled? (and offscreen? (:settle-offscreen? appearance))
          defer? (and (not= false (:viewport? appearance))
                     (not (:force appearance)) offscreen? (not settled?)
                     (or (not (:skip-enter? state)) (:document-enter? state)))]
      ;; Layout entrances must retain their natural offscreen height. Waiting
      ;; for a zero-height row to intersect would also hide the rows below it.
      (reset! (:settled-offscreen? state) (boolean settled?))
      (when settled?
        (reset! (:settling-offscreen? state) true)
        (reset! (:visible? state) true)
        ;; Commit natural height with transitions disabled, then restore them
        ;; after paint so later user folds/reopens still animate normally.
        (swap! (:stops state) assoc :offscreen-settle
               (next-frame! #(reset! (:settling-offscreen? state) false))))
      (reset! (:viewport-enter? state) defer?)
      (when defer? (reset! (:visible? state) false))))
  (let [appearance (config (or (:seen options) (:appear options)))
        automatic? @(:viewport-enter? state)
        seen (or (:seen options) automatic?)
        threshold (or (:threshold appearance) (if automatic? 0 0.5))
        ;; seen-merge sampled in 20% steps, deciding visible at 50%. Observing
        ;; exactly 50% lets a slide reverse itself as its transform crosses that
        ;; same boundary. Preserve the original sampling for the default.
        legacy? (= threshold 0.5)
        thresholds (if legacy? [0 0.2 0.4 0.6 0.8 1] threshold)
        once? (or automatic? (boolean (:once? appearance)))
        margin (or (:root-margin appearance) "0px")
        reduced? @(:reduced? state)
        ;; Ready first-screen SSR remains visible. Offscreen :seen owners still
        ;; observe the viewport after hydration and enter when scrolled into view.
        skip? (or @(:settled-offscreen? state)
                  (and (:skip-enter? state) (not (:observe-document? state)) (not automatic?)))
        signature [element (boolean seen) (boolean (:appear options)) thresholds once? margin reduced? skip? (:force appearance)]]
    (when (not= signature @(:signature state))
      (stop! state :watch)
      (reset! (:signature state) signature)
      (let [cleanup! (when element
                       (cond
                         (or skip? reduced? (:force appearance)
                             (not (or (:appear options) seen)))
                         (do (reset! (:visible? state) true) nil)
                         (and seen (exists? js/IntersectionObserver))
                         (let [*seen? (atom false)
                               observer (js/IntersectionObserver.
                                          (fn [entries observer]
                                            (when @(:active? state)
                                              (doseq [entry (array-seq entries)]
                                                (let [ratio (.-intersectionRatio entry)
                                                      ratio' (if legacy? (/ (js/Math.floor (* ratio 100)) 100) ratio)
                                                      visible? (and (.-isIntersecting entry)
                                                                    (>= ratio' threshold))]
                                                  (when-not (and once? @*seen?)
                                                    (reset! (:visible? state) visible?)
                                                    (when visible? (reset! *seen? true)))
                                                  (when (and once? visible?) (.disconnect observer))))))
                                          (clj->js {:threshold thresholds, :rootMargin margin}))]
                           (.observe observer element)
                           #(.disconnect observer))
                         :else (next-frame! #(when @(:active? state)
                                               (reset! (:visible? state) true)))))]
        (when cleanup! (swap! (:stops state) assoc :watch cleanup!))))
    (when (and (:remember-key appearance) @(:visible? state)
               (not (:remembered? options)))
      (r/dispatch [:component-motion/seen (:remember-key appearance)]))
    (let [presence (:presence options)
          signature [presence (:exit options) reduced? element]]
      (when (not= signature @(:presence state))
        (stop! state :exit)
        (reset! (:presence state) signature)
        (when (and (= :exiting (:phase presence)) (:finish! presence))
          (let [cleanup! (if (or reduced? (nil? element) (not (:exit options)))
                           (let [*active? (atom true)]
                             (js/queueMicrotask #(when @*active? ((:finish! presence))))
                             #(reset! *active? false))
                           (finish-animation! element (config (:exit options)) (:finish! presence)))]
            (swap! (:stops state) assoc :exit cleanup!)))))))

(def motion-feature
  {:setup (fn [options]
            (let [skip? (skip-enter? options)]
              {:options options
               :skip-enter? skip?
               :document-enter? (and skip? (restore/document-enter?)
                                     (:appear options) (not (:seen options))
                                     (not (:remembered? options)))
               :observe-document? (and skip? (restore/document-enter?) (:seen options)
                                      (not (:remembered? options)))
               :visible? (r/atom skip?)
               :viewport-enter? (atom nil)
               :settled-offscreen? (r/atom false)
               :settling-offscreen? (r/atom false)
               :reduced? (r/atom (boolean (and (some options [:appear :seen :exit])
                                              (reduced-motion?))))
               :active? (atom true)
               :signature (atom nil)
               :presence (atom nil)
               :stops (atom {})}))
   ;; Changes are committed through the render callback. Keep the original
   ;; hydration/entrance decision and live owner when only configuration changes.
   :mount (fn [state element] (commit-motion! state element (:options state)))
   :reconfigure (fn [state _] state)
   :unmount stop-all!
   :render (fn [form options state]
             (when (and form (not (dom-root? form)))
               (throw (js/Error. "Motion features require a native DOM root; they never add a wrapper.")))
             (let [appearance (config (or (:seen options) (:appear options)))
                   exiting? (= :exiting (get-in options [:presence :phase]))
                   visible? (if state @(:visible? state) (skip-enter? options))
                   reduced? (boolean (and state @(:reduced? state)))
                   settling? (boolean (and state @(:settling-offscreen? state)))
                   document-enter? (if state (and (:document-enter? state) (:appear options)
                                                  (not @(:settled-offscreen? state))
                                                  (not (:seen options)) (not @(:viewport-enter? state)))
                                     (and (skip-enter? options) (restore/document-enter?)
                                          (:appear options) (not (:seen options))
                                     (not (:remembered? options))))
                   base (attrs form)
                   classes (string/join " " (remove string/blank?
                                                  [(class-text (:class base))
                                                   "appear-wrapper" (class-text (:class appearance))
                                                   (when (and (not exiting?) (or visible? reduced?)) "appeared")
                                                   (when exiting? (str "exiting " (class-text (:class (config (:exit options))))))]))]
               {:form (when form
                        (with-attrs form
                          (cond-> (assoc base :class classes)
                            document-enter? (assoc :data-document-appear true)
                            settling? (assoc :data-appear-settling true)
                            (or reduced? settling?) (update :style merge {:transition "none", :animation "none"})
                            exiting? (assoc :aria-hidden true :inert true))))
                :commit! (when state #(commit-motion! state % options))}))})

(defn- flatten-children [forms]
  (mapcat #(if (and (sequential? %) (not (vector? %))) (flatten-children %) [%]) forms))

(defn- retained-children [form eligible? state]
  (let [current (vec (flatten-children (children form)))
        keyed (fn [child]
                (when (and (vector? child) (eligible? child))
                  (or (:key (meta child))
                      (throw (js/Error. "Presence children with :exit need stable, unique Hiccup keys.")))))
        keys (keep keyed current)
        ;; A child whose exit feature was disabled is still present. Never keep
        ;; a duplicate of its old, eligible form just because eligibility changed.
        present-keys (into #{} (keep #(when (vector? %) (:key (meta %)))) current)
        previous (if state @(:committed state) [])
        next (mapv (fn [child]
                     (let [key (keyed child)]
                       {:key key, :form child, :presence (when key present)})) current)
        removed (keep-indexed (fn [index entry]
                                (when (and (:key entry) (not (present-keys (:key entry))))
                                  [index entry])) previous)]
    (when (not= (count keys) (count (set keys)))
      (throw (js/Error. "Presence children require unique keys.")))
    (reduce
      (fn [entries [index entry]]
        (let [key (:key entry)
              control (if (= :exiting (get-in entry [:presence :phase]))
                        (:presence entry)
                        (let [token (js-obj)]
                          (->Presence :exiting token
                                      (fn []
                                        (when (and @(:active? state)
                                                   (some #(and (= key (:key %))
                                                               (identical? token (get-in % [:presence :token])))
                                                         @(:committed state)))
                                          (swap! (:committed state)
                                                 #(vec (remove (fn [entry] (= key (:key entry))) %)))
                                          (swap! (:revision state) inc))))))
              index' (min index (count entries))]
          (into (conj (subvec entries 0 index') (assoc entry :presence control))
                (subvec entries index')))) next removed)))

(defn- commit-presence! [state retained eligible?]
  (reset! (:committed state) retained)
  (let [exiting (into {} (keep (fn [entry]
                               (when (= :exiting (get-in entry [:presence :phase]))
                                 [(:key entry) entry]))) retained)]
    (doseq [[key {:keys [token timer]}] @(:deadlines state)
            :when (not (identical? token (get-in exiting [key :presence :token])))]
      (js/clearTimeout timer)
      (swap! (:deadlines state) dissoc key))
    (doseq [[key entry] exiting :when (not (contains? @(:deadlines state) key))]
      (let [control (:presence entry)
            options (config (eligible? (:form entry)))
            timeout (if (reduced-motion?) 0 (min 10000 (or (:timeout-ms options) 10000)))
            timer (js/setTimeout (:finish! control) timeout)]
        (swap! (:deadlines state) assoc key {:token (:token control), :timer timer})))))

(def presence-feature
  {:root? false
   :reconfigure (fn [state _] state)
   :setup (fn [_]
            ;; A commit records the latest Hiccup, including newly allocated
            ;; callbacks. Recording it must not schedule another render. Only
            ;; asynchronous exit completion invalidates the presence owner.
            {:committed (atom [])
             :revision (r/atom 0)
             :active? (atom true)
             :deadlines (atom {})})
   :unmount (fn [state]
              (reset! (:active? state) false)
              (doseq [[_ {:keys [timer]}] @(:deadlines state)] (js/clearTimeout timer))
              (reset! (:deadlines state) {}))
   :render (fn [form eligible? state]
             (when state @(:revision state))
             (when (and form (not (or (dom-root? form) (= :<> (first form)))))
               (throw (js/Error. "The :presence feature needs a native parent root or fragment.")))
             (let [retained (when form (retained-children form eligible? state))]
               {:form (when form (with-meta
                        (into [(first form) (attrs form)]
                              (map (fn [{:keys [form presence]}]
                                     (if presence (conj form presence) form)) retained))
                        (meta form)))
                :commit! (when (and state form) (fn [_] (commit-presence! state retained eligible?)))}))})
