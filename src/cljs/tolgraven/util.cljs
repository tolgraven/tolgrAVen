(ns tolgraven.util
  (:require [re-frame.core :as rf]
            [cljsjs.highlight :as hljs]
            [cljs-time.core :as ct]
            [cljs-time.format :as ctf]
            [cljs-time.coerce :as ctc]
            [clojure.string :as string]
            [clojure.pprint :as pprint]
            [clojure.walk :as walk]))

(defn <-store [& coll-docs]
  (-> @(rf/subscribe [:firestore/on-snapshot {:path-document coll-docs}])
      :data
      (walk/keywordize-keys)))

(defn normalize-firestore "Reformat data for our app-db storage. Only works for documents? And only blog-posts lol wth"
  [response]
  (let [ks (reduce (fn [ks v]
                     (conj ks (get-in v [:data "id"])))
                   []
                   (:docs response))]
    (zipmap ks
            (walk/keywordize-keys  
             (map :data (:docs response))))))

(defn normalize-firestore-general "Reformat data for our app-db storage. Fix so compat w blog-posts..."
  [response]
  (let [docs (or (:docs response) response)
        ks (reduce (fn [ks v]
                     (conj ks (-> v :id keyword)))
                   []
                   docs)]
    (zipmap ks
            (walk/keywordize-keys  
             (map :data docs)))))


(defmacro handler-fn "Use in event-handlers instead of (fn [e/_]), returns nil so react doesnt get a false and ignore us"
  ([& body]
    `(fn [~'event] ~@body nil)))  ;; force return nil

(defmacro ors "(or), but treats empty string as nil. not working"
  ([] nil)
  ([x] x)
  ([x & next]
   `(let [or# ~x]
          (if or# or# (ors ~@next)))))

(defn interleave-all "interleaves including remainder of longer seqs." ;from SO, @SomeRando
  [& seqs]
  (when (not-empty (first seqs))
    (let [remaining (filter not-empty
                            (concat (rest seqs)
                                    [(rest (first seqs))]))]
      (cons (first (first seqs))
            (lazy-seq (apply interleave-all remaining))))))

(defn log "Log to both console and app" ;XXX should add an endpoint to timbre instead.
 ([message] (log :debug "" message))
 ([level title message & messages]
  (let [msg (string/join " " (into [message] messages))
        js-fn (case level
                :debug js/console.debug
                :error js/console.error
                :warning js/console.warn
                :info js/console.log)]
   ; (println (string/capitalize (name level)) "\t" message)
   (js-fn message)
   (rf/dispatch [:diag/new level title msg]))))

(defn log-errors [errors]
 (when (seq errors)
  (log :debug "Error" errors))) ;debug instead of error, dont want to spam the hud


(defn pluralize "Swap 0/no and grammar"
  [amount thing]
  (str amount ;(if (zero? amount) "No" amount)
       " " thing
       (when-not (= 1 amount) "s")))


(defn timestamp "Use 0-59 mins ago, 1-24 hrs ago, datestamp..."
  [ts]
  (if-let [ts (ctc/from-long ts)]
    (let [mins (ct/mins-ago ts)]
      (if (zero? mins)
        "now"
        (if (< mins 60)
        (str (pluralize mins "minute") " ago")
        (let [day-ago (ct/minus (ct/now) (ct/hours 24))]
          (if (ct/after? ts day-ago)
            (str (pluralize (int (/ mins 60)) "hour") " ago")
            (ctf/unparse (ctf/formatters :date) ts))))))
    ""))

(defn unix->ts "Convert unix time to human-readable"
  [ms & [custom-format]]
  (let [custom-format (or custom-format :hour-minute-second)]
    (ctf/unparse (ctf/formatters custom-format)
                 (ctc/from-long ms))))


(defn format-number [n & [precision]]
  (let [precision (or precision 3)]
    (case n
      (nil, 0) 0
      (try
       (-> (pprint/cl-format nil (str "~," precision "f") n)
           js/parseFloat)
       (catch js/Error e n)))))

(defn abs [n]
  (max n (- n)))

(defn rescale-to-frac [n min-input max-input]
  (max 0.0 (min 1.0 (/ (- n min-input) (- max-input min-input)))))

(defn- format-css-var [var-name]
  (or (and (some? (string/index-of var-name "--"))
           var-name)
      (str "--" var-name)))

(defn <-css-var "Get value of CSS variable. Have to apply to dummy div to force calc"
  [var-name & calc]
  (if-not calc
    (try
     (when-let [v (-> js/document.documentElement
                      js/getComputedStyle
                      ; here where'd need to force calc?
                      (.getPropertyValue (format-css-var var-name)))]
          (cond-> v
            (string? v) string/trim
            (object? v) js->clj))
     (catch js/Error e "")))); else have some dummy div we literally apply stupid css to with the calced fucker...  goddamn. fix some other time then.

(defn ->css-var "Set value of CSS variable.
                 Causing issues 'no prot method IMap.-dissoc defined for
                 [object CSSStyleDeclaration]' - in interceptor, is re-frisk related or?"
  [var-name value]
  (when (and var-name (some? value))
    (try (doto js/document.documentElement.style
        (.setProperty (format-css-var var-name) value))
         (catch js/Error e))))

(defn css-str "string builder"
 [f & args] (str f "(" (string/join ", " args) ")"))
(defn css-arg [& parts] (string/join " " parts))
(defn css-rot [angle] (str "rotate(" angle "deg)"))

(defn cubic-bezier [& args] (apply css-str "cubic-bezier" args))

(defn make-key "Make key easily for React lists..." [& ids] ;could this return the meta straight away instead
 (clojure.string/join "-" (map #(if (keyword? %) (name %) (str %)) ids)))


(defn cs [& names] (string/join " " (filter identity names)))

(defn add-attrs "Put more attrs in something already defined..."
  [component attrs]
  (if (map? (second component))
      (update component 1 merge attrs) ; oh yeah forgot about update-in going inside heh. also not even needed!
      [(first component) attrs (rest component)])) ; eh uh but

(defn deep-merge "Recursively merge maps. If vals are not maps, the last value wins."
 [& values]
 (if (every? map? values)
  (apply merge-with deep-merge values)
  (last values)))

(defn remove-nils "Remove keys with nil or empty seq values, even in nested maps."
  [m]
  (let [f (fn [[k v]] (when (if (coll? v) (seq v) v) [k v]))]
    (clojure.walk/postwalk
     (fn [x] (if (and (map? x) (not (record? x)))
               (into {} (map f x))
               x))
     m)))


(defn at "Take symbol: if value, return it. If ratom, deref it, thereby avoiding nil derefing etc..."
 [val-or-atom & fallback]
 (if (satisfies? IDeref val-or-atom) ;is satisfies equally slow on js? investigate...
  @val-or-atom
  (or val-or-atom fallback)))


(defn playing? "Check if video is stopped"
  [video]
  (when video
    (and (pos? (.-currentTime video))
         (not (.-paused video))
         (not (.-ended video))
         (< 2 (.-readyState video)))))

; this sometimes gets stuck on infinite loop dragging down page speed
; or actually if only triggers 330ms apart dunno but either way.
; i guess if something triggers opposite ways at same time will never arrive. new checks enough?
(defn play-pauser "Returns fn to :play or :pause video smoothly by changing playback rate progressively (calls itself in intervals)"
  [video & {:keys [rate-step time-per-step]
            :or {rate-step 0.34 time-per-step 330}}] ; or makes more sense passing desired transition time, syncing that w css vars...
  (let [state (atom (if (playing? video) :playing :paused)) ; :playing, :to-play, :paused, :to-pause
        speed (atom (case @state :paused 0 :playing 1)) ]
    (fn play-pause-updater [action] ; if endless retrigger ends up hapening outside dev, switch recur args to :towards-play/pause so sep initial trigger? or count calls. or get cancellable dispatch-later working...
      (when-not (or (not video)
                    (and (some #{:playing :to-pause} [@state]) (= :play action))
                    (and (some #{:paused :to-play} [@state]) (= :pause action))) ;ignore action if already done
        (reset! speed (case action  ; actually update playback speed
                        :pause (max 0 (- @speed rate-step))
                        :play  (min 1 (+ @speed (* 0.8 rate-step))) ;haha quick hack to avoid endless duel... sort proper tho
                        @speed))
        (reset! state (cond
                         (>= 0.05 @speed) :paused
                         (<= 1.0 @speed) :playing
                         :else (case action ; only needs doing first time but easier this way
                                 :play :to-play
                                 :pause :to-pause)))
        (set! (.-playbackRate video) @speed)
        (set! (.-muted video) true)
        (if (some #{:playing :to-play :to-pause} [@state])
          (when-not (playing? video)
            (.play video)) ;how can we get exception "video.play not a fn" when pref above true? and never call unless el exists...
          (when (playing? video)
            (.pause video))) ;sometimes get uncaught exceptions about call to play interrupted by pause yada...
        (when (some #{:to-play :to-pause} [@state])
          (rf/dispatch [:run-in! :play-pauser time-per-step
                        play-pause-updater action]))))))


(defn elem-by-id [id]
  (.getElementById js/document id))
(defn on-window [event f]
 (js/window.addEventListener event f))
(defn on-document [event f & [opt-map]]
 (js/document.addEventListener event f (clj->js opt-map)))
(defn remove-on-document [event f & [opt-map]]
 (js/document.removeEventListener event f (clj->js opt-map)))

(defn on-event [node event f & [active?]]
 (let [node (if (or (keyword? node) (string? node))
              (elem-by-id (name node))
              node)]
  (.addEventListener node event f active?)))



(defn bounding-rect [e]
 (.getBoundingClientRect (.-target e)))
(defn client-xy [e]
 [(.-clientX e) (.-clientY e)])

(defn elem-in-view? "Returns true when element is visible" [el]
  (let [rect (.getBoundingClientRect el)
        [top bottom] [(.-top rect) (.-bottom rect)]]
    (and (< top js/window.innerHeight) (>= bottom 0))))

(defn rem-to-px "Convert rem to pixels. Doesnt seem like gets quite perfect..."
  [rem-val]
  (-> (js/getComputedStyle js/document.documentElement)
      .-fontSize
      js/parseFloat
      (* rem-val)))

(defn px-to-rem "Convert pixels to rem. Doesnt seem like gets quite perfect..."
  [px-val]
  (->> (js/getComputedStyle js/document.documentElement)
       .-fontSize
       js/parseFloat
       (/ px-val)))

(defn run-highlighter! [el-type & [div-ref]]
  (let [selected (-> (or div-ref js/document) ;cant querySelectorAll on document?
                     (.querySelectorAll el-type))]
    (doseq [el selected]
      (js/hljs.highlightBlock el)))) ;uh doseq more appropriate no


(defn scroll-by "Manually scroll view by. But guess need custom for Safar-eye"
  [rem & [id]] ; want to scroll within container as well?
  (doto (or (when id elem-by-id) js/window)
    (.scrollBy 0 (rem-to-px rem))))

(defn scroll-to "Manual so Safari gets same fn as chrome
                 Plus move page down correctly when open menu etc...
                 There is a CSS offset thing but cant get it working?"
  [id & [offset]] ; should have bangs, also take parent id like -by, if necessary?
  (when-let [id (elem-by-id id)]
     (.scrollIntoView id (clj->js
                          {:behavior :smooth, :block :start}))
     (when offset
       (scroll-by offset))))

(defn scroll-to-px "Pixels, simply"
  [scroll-y-px] ; should have bangs, also take parent id like -by, if necessary?
  (.scrollTo js/window 0 scroll-y-px))

(defn observer [on-view-change & [id]] ;what's with the weird scrolling bug?
  (let [in-view (atom 0.0)
        on-change (fn [[entry & _]]
                    (let [frac (-> (.-intersectionRatio entry)
                                   (* 100) int
                                   float (/ 100))]
                      (when-not (= @in-view frac)
                        (on-view-change (reset! in-view frac)))))
        opts {:threshold [0 0.2 0.4 0.6 0.8 1.0]}
        observer (js/IntersectionObserver. on-change (clj->js opts))]
    (fn [el]
      (if el
        (.observe observer el)
        (.disconnect observer))))) ;prob needs tearing down for reload or?

(defn xy-in-rect [e dimension rect]
 (let [m {:x (- (.-clientX e) (.-left rect)) ;XXX shouldnt do unneccessary work tho
          :y (- (.-clientY e) (.-top rect))}]
  (map m dimension))) ;ok so now will return vec even for one dim

(defn resize-canvas-to-display-size
  [canvas]
  (when canvas
    (let [[w h] [(.-clientWidth canvas) (.-clientHeight canvas)]
          needs-resize (or (not= (.-width canvas) w)
                           (not= (.-height canvas) h))]
      (when needs-resize
        (set! (.-width canvas) w)
        (set! (.-height canvas) h)))))
