(ns tolgraven.component.motion
  "Root-merged motion and parent-owned exit retention. No wrapper components."
  (:require [clojure.string :as string]
            [tolgraven.react :as rf]
            [tolgraven.component.restore :as restore]))

(rf/reg-event-db :component-motion/seen
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

(defn- next-frame! [callback]
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

(defn use-motion
  "Merge appearance/visibility/exit state onto this component's native root."
  [form options presence]
  (let [appear (:appear options) seen (:seen options) exit (:exit options)
        appearance (config (or seen appear)) exit-options (config exit)
        remember-key (:remember-key appearance)
        remembered? (when remember-key @(rf/subscribe [:state [:motion-seen remember-key]]))
        [skip-enter?] (rf/use-state #(boolean (and (not= false (:restore? appearance))
                                                  (or remembered? (restore/skip-enter?)))))
        [initial-enter?] (rf/use-state #(boolean (and (not= false (:restore? appearance)) (restore/initial-enter?))))
        [visible? set-visible!] (rf/use-state skip-enter?)
        [reduced? set-reduced!] (rf/use-state reduced-motion?)
        *element (rf/use-ref nil)
        base-ref (:ref (attrs form))
        capture! (rf/use-callback
                  (fn [element] (set! (.-current *element) element) (set-ref! base-ref element) js/undefined)
                  #js [base-ref])
        exiting? (= :exiting (:phase presence))
        threshold (or (:threshold appearance) 0.5)
        once? (boolean (:once? appearance))
        root-margin (or (:root-margin appearance) "0px")]
    (rf/use-effect
     (fn []
       (when (and remember-key visible? (not remembered?))
         (rf/dispatch [:component-motion/seen remember-key]))
       js/undefined) #js [remember-key visible? remembered?])
    (rf/use-effect
     (fn []
       (if-not (and (exists? js/window) (.-matchMedia js/window))
         js/undefined
         (let [query (.matchMedia js/window "(prefers-reduced-motion: reduce)")
               change! #(set-reduced! (.-matches query))]
           (.addEventListener query "change" change!)
           #(.removeEventListener query "change" change!)))) #js [])
    ;; Run after every commit, but only restart when root/options change.
    ;; A ref replacement with the same DOM element does not reset visibility.
    (let [*watch (rf/use-ref nil)]
      (rf/use-layout-effect
       (fn []
         (let [element (.-current *element)
               signature [element (boolean seen) threshold once? root-margin reduced? skip-enter? (:force appearance)]
               old (.-current *watch)]
           (when (not= signature (:signature old))
             (when-let [stop! (:stop! old)] (stop!))
             (let [stop! (when element
                           (cond
                             (or skip-enter? reduced? (:force appearance) (not (or appear seen)))
                             (do (set-visible! true) nil)
                             (and seen (exists? js/IntersectionObserver))
                             (let [*seen? (atom false)
                                   observer (js/IntersectionObserver.
                                             (fn [entries observer]
                                               (doseq [entry (array-seq entries)]
                                                 (let [in-view? (and (.-isIntersecting entry)
                                                                     (>= (.-intersectionRatio entry) threshold))]
                                                   (when-not (and once? @*seen?)
                                                     (set-visible! in-view?)
                                                     (when in-view? (reset! *seen? true)))
                                                   (when (and once? in-view?) (.disconnect observer)))))
                                             #js {:threshold threshold :rootMargin root-margin})]
                               (.observe observer element)
                               #(.disconnect observer))
                             :else (next-frame! #(set-visible! true))))]
               (set! (.-current *watch) {:signature signature :stop! stop!}))))
         js/undefined))
      (rf/use-layout-effect (fn [] (fn []
                                      (when-let [stop! (:stop! (.-current *watch))] (stop!))
                                      (set! (.-current *watch) nil))) #js []))
    (rf/use-layout-effect
     (fn []
       (if-not (and exiting? (:finish! presence))
         js/undefined
         (if (or reduced? (nil? (.-current *element)))
           ;; Parent's commit must finish before its child can acknowledge exit.
           (let [*active? (atom true)]
             (js/queueMicrotask #(when @*active? ((:finish! presence))))
             #(reset! *active? false))
           (finish-animation! (.-current *element) exit-options (:finish! presence)))))
     #js [presence reduced?])
    (when (and form (not (dom-root? form)))
      (throw (js/Error. "Motion features require a native DOM root; they never add a wrapper.")))
    (when form
      (let [base (attrs form)
            classes (string/join " " (remove string/blank?
                                              [(class-text (:class base))
                                               "appear-wrapper" (class-text (:class appearance))
                                               (when (and (not exiting?) (or visible? reduced? skip-enter?)) "appeared")
                                               (when exiting? (str "exiting " (class-text (:class exit-options))))]))]
        (with-attrs form
          (cond-> (assoc base :class classes :ref capture!
                                :data-stream-enter (when initial-enter? true))
            reduced? (update :style merge {:transition "none" :animation "none"})
            exiting? (assoc :aria-hidden true :inert true)))))))

(defn- flatten-children [forms]
  (mapcat #(if (and (sequential? %) (not (vector? %))) (flatten-children %) [%]) forms))

(defn use-presence
  "Retain removed keyed direct children in their existing parent until they exit.
   eligible? identifies defc children opting into :exit; no virtual/DOM wrappers."
  [form eligible?]
  (let [*committed (rf/use-ref [])
        *mounted? (rf/use-ref true)
        *deadlines (rf/use-ref {})
        [_ refresh!] (rf/use-reducer inc 0)
        current (vec (flatten-children (children form)))
        keyed (fn [child] (when (and (vector? child) (eligible? (first child)))
                           (or (:key (meta child))
                               (throw (js/Error. "Presence children with :exit need stable, unique Hiccup keys.")))))
        keys (keep keyed current)
        previous (.-current *committed)
        next (mapv (fn [child]
                     (let [key (keyed child)]
                       {:key key :form child :presence (when key present)})) current)
        removed (keep-indexed (fn [index entry]
                                (when (and (:key entry) (not (some #{(:key entry)} keys))) [index entry])) previous)
        retained (reduce
                  (fn [entries [index entry]]
                    (let [key (:key entry)
                          control (if (= :exiting (get-in entry [:presence :phase])) (:presence entry)
                                    (let [token (js-obj)]
                                      (->Presence :exiting token
                                                  (fn []
                                                    (when (and (.-current *mounted?)
                                                               (some #(and (= key (:key %))
                                                                           (identical? token (get-in % [:presence :token])))
                                                                     (.-current *committed)))
                                                      (set! (.-current *committed)
                                                            (vec (remove #(= key (:key %)) (.-current *committed))))
                                                      (refresh!))))))
                          index (min index (count entries))]
                      (into (conj (subvec entries 0 index) (assoc entry :presence control)) (subvec entries index))))
                  next removed)]
    (when (not= (count keys) (count (set keys)))
      (throw (js/Error. "Presence children require unique keys.")))
    (when-not (or (dom-root? form) (= :<> (first form)))
      (throw (js/Error. "The :presence feature needs a native parent root or fragment.")))
    (rf/use-layout-effect
     (fn []
       (set! (.-current *committed) retained)
       (let [exiting (into {} (keep (fn [entry]
                                     (when (= :exiting (get-in entry [:presence :phase]))
                                       [(:key entry) entry])) retained))]
         (doseq [[key {:keys [token timer]}] (.-current *deadlines)
                 :when (not (identical? token (get-in exiting [key :presence :token])))]
           (js/clearTimeout timer)
           (set! (.-current *deadlines) (dissoc (.-current *deadlines) key)))
         (doseq [[key entry] exiting :when (not (contains? (.-current *deadlines) key))]
           (let [control (:presence entry)
                 options (config (eligible? (first (:form entry))))
                 ;; This deadline also covers loading/error roots that cannot
                 ;; acknowledge an exit themselves. It owns no DOM or wrapper.
                 timeout (if (reduced-motion?) 0 (min 10000 (or (:timeout-ms options) 10000)))
                 timer (js/setTimeout (:finish! control) timeout)]
             (set! (.-current *deadlines)
                   (assoc (.-current *deadlines) key {:token (:token control) :timer timer})))))
       js/undefined))
    (rf/use-layout-effect (fn [] (set! (.-current *mounted?) true)
                             (fn [] (set! (.-current *mounted?) false)
                               (doseq [[_ {:keys [timer]}] (.-current *deadlines)] (js/clearTimeout timer))
                               (set! (.-current *deadlines) {})
                               (set! (.-current *committed) []))) #js [])
    (with-meta
      (into [(first form) (attrs form)]
            (map (fn [{:keys [form presence]}] (if presence (conj form presence) form)) retained))
      (meta form))))
