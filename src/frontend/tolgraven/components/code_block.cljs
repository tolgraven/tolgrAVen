(ns tolgraven.components.code-block
  "Controls hydrate immediately; the formatter hydrates after the page is ready."
  (:require [clojure.string :as string]
            [tolgraven.component.registry]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.hydration :as hydration]
            [tolgraven.browser-resources :as resources]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.render-context :as context]
            [tolgraven.loader :as loader]
            [tolgraven.listener :as listener]
            [tolgraven.loader.view :as view]))

(def options-schema
  [:map
   [:language {:optional true} [:maybe :string]]
   [:default-language {:optional true} [:maybe :string]]
   [:auto-languages {:optional true} [:maybe [:vector :string]]]
   [:inline-language {:optional true} [:maybe :string]]
   [:style {:optional true} [:or :map [:fn object?]]]
   [:basic? {:optional true} :boolean]
   [:inline? {:optional true} :boolean]
   [:wrap? {:optional true} :boolean]
   [:line-numbers? {:optional true} :boolean]
   [:starting-line-number {:optional true} [:int {:min 1}]]
   [:foldable? {:optional true} :boolean]
   [:folded? {:optional true} :boolean]
   [:fold-lines {:optional true} [:int {:min 1}]]])

;; Preserve the public keyword/value API, with the same contracts as map options.
(def args-schema
  [:cat :string
   [:alt
    [:cat options-schema]
    [:* (into [:alt]
          (map (fn [[key _ schema]] [:cat [:= key] schema])
               (rest options-schema)))]]])

(defn options-map [options]
  (if (and (= 1 (count options)) (map? (first options)))
    (first options)
    (apply hash-map options)))

(defc <formatted> [component args committed!]
  (rf/use-layout-effect (fn [] (committed!) js/undefined) #js [])
  (into [component] args))

(defn- make-formatter [committed!]
  ;; Construction is pure. The owned effect releases this gate; only then can
  ;; React.lazy acquire code through the existing parallel CSS/JS loader.
  (let [*release (atom nil)
        gate (js/Promise. (fn [resolve _] (reset! *release resolve)))]
    {:release! #(@*release nil)
     :view (rf/lazy
             (fn []
               (-> gate
                   (.then (fn [_] (loader/acquire-code! :highlight)))
                   (.then (fn [spec]
                            (let [component (get-in spec [:view :code-block])
                                  component (if (var? component) @component component)]
                              #js {:default (rf/reactify-component
                                              (fn [{:keys [args]}]
                                                [<formatted> component args committed!]))}))))))}))

(defc <formatter> [code options]
  (let [[defer? _] (rf/use-state #(and (not context/*server?*) (restore/initial-hydration?)))
        committed! (hydration/use-deferred! defer?)
        [formatter _] (rf/use-state #(make-formatter committed!))
        args (into [code] (mapcat identity options))
        fallback (if (:inline? options) [:code.code-highlight code] [:pre [:code code]])]
    (rf/use-effect
      (fn []
        (if defer?
          (resources/after-page! (:release! formatter))
          (do ((:release! formatter)) js/undefined)))
      #js [])
    ;; Reagent defc memoizes this boundary independently of outer controls.
    [rf/suspense {:fallback (rf/as-element fallback)}
     (if context/*server?*
       (view/form :highlight :code-block args fallback)
       ;; Keep Clojure arguments opaque at this native React boundary.
       (rf/create-element (:view formatter) #js {:args args}))]))

(rf/reg-event-fx :code-block/copy
  {:args [:cat :string fn?]}
  (fn [_ [_ text complete!]]
    {:code-block/copy {:text text
                       :complete! complete!}}))
(rf/reg-fx :code-block/copy
  (fn [{:keys [text complete!]}]
    (if-let [clipboard (when (exists? js/navigator) (.-clipboard js/navigator))]
      (-> (.writeText clipboard text)
          (.then #(complete! "Copied"))
          (.catch #(complete! "Copy failed")))
      (complete! "Copy unavailable"))))

(defn- use-copy-feedback [code]
  (let [[label set-label!] (rf/use-state "Copy")
        *owner (rf/use-ref nil)]
    (rf/use-effect
      (fn []
        (set! (.-current *owner) {:token (gensym "copy") :request 0 :timer nil})
        (set-label! "Copy")
        (fn []
          (js/clearTimeout (:timer (.-current *owner)))
          (set! (.-current *owner) nil)))
      #js [code])
    [label
     (rf/use-callback
       (fn []
         (when-let [{:keys [token request timer] :as owner} (.-current *owner)]
           (let [request (inc request)]
             (js/clearTimeout timer)
             (set! (.-current *owner) (assoc owner :request request :timer nil))
             (set-label! "Copying…")
             (rf/dispatch
               [:code-block/copy code
                (fn [result]
                  ;; Ignore stale clipboard completions after another copy, new
                  ;; contents, or unmount. The timer belongs to this component.
                  (let [current (.-current *owner)]
                    (when (and (= token (:token current))
                               (= request (:request current)))
                      (set-label! result)
                      (set! (.-current *owner)
                        (assoc current :timer (js/setTimeout #(set-label! "Copy") 1800))))))]))))
       #js [code])]))

(defn- inside? [element x y]
  (let [bounds (.getBoundingClientRect element)]
    (and (<= (.-left bounds) x (.-right bounds))
         (<= (.-top bounds) y (.-bottom bounds)))))

(defn- selected? [element]
  (let [selection (.getSelection js/window)]
    (and selection
         (not (.-isCollapsed selection))
         (or (.contains element (.-anchorNode selection))
             (.contains element (.-focusNode selection))))))

(defn- control-target? [target]
  (some-> target (.closest "button, a, input, textarea, select, [contenteditable=true]")))

(defn- use-copy-gesture [copy!]
  (let [*element (rf/use-ref nil)]
    (rf/use-effect
      (fn []
        (when-let [element (.-current *element)]
          ;; React holds events aimed at an unhydrated Suspense child. Capture
          ;; native pointer intent above its root so SSR text can be copied
          ;; without releasing the formatter gate. React still owns all markup.
          (let [token (gensym "code-pointer")
                *press (atom nil)
                gesture-events ["pointermove" "pointerout" "pointerup" "pointercancel"]
                cancel! (fn []
                          (reset! *press nil)
                          (doseq [event gesture-events] (listener/remove! [token event])))
                register! (fn [event handler]
                            (listener/register! {:id [token event]
                                                 :owner :code-block
                                                 :target js/document
                                                 :event event
                                                 :handler handler
                                                 :capture? true}))
                handlers
                {"pointermove"
                 (fn [event]
                   (when-let [{:keys [id x y]} @*press]
                     (when (and (= id (.-pointerId event))
                                ;; CSS-pixel tolerance is pointer measurement,
                                ;; not styling. Any exit/drag cancels on reentry.
                                (or (> (js/Math.hypot (- x (.-clientX event))
                                                     (- y (.-clientY event))) 4)
                                    (not (inside? element (.-clientX event) (.-clientY event)))))
                       (cancel!))))
                 "pointerout"
                 (fn [event]
                   (when (and (= (:id @*press) (.-pointerId event))
                              (.contains element (.-target event))
                              (not (.contains element (.-relatedTarget event))))
                     (cancel!)))
                 "pointercancel" (fn [_] (cancel!))
                 "pointerup"
                 (fn [event]
                   (when-let [{:keys [id x y]} @*press]
                     (when (= id (.-pointerId event))
                       (cancel!)
                       (when (and (not (control-target? (.-target event)))
                                  (<= (js/Math.hypot (- x (.-clientX event))
                                                    (- y (.-clientY event))) 4)
                                  (inside? element (.-clientX event) (.-clientY event))
                                  (not (selected? element)))
                         (copy!)))))}]
            (register! "pointerdown"
              (fn [event]
                (when (or @*press (.contains element (.-target event)))
                  (cancel!)
                  (when (and (.contains element (.-target event))
                             (.-isPrimary event)
                             (zero? (.-button event))
                             (not (control-target? (.-target event))))
                    (reset! *press {:id (.-pointerId event)
                                   :x (.-clientX event)
                                   :y (.-clientY event)})
                    ;; Movement/up listeners exist only during this press.
                    (doseq [[event handler] handlers] (register! event handler))))))
            (fn []
              (cancel!)
              (listener/remove! [token "pointerdown"])))))
      #js [copy!])
    *element))

(defc <code-block>
  {:args-schema args-schema}
  [code & options]
  (let [options (options-map options)
        [wrap? set-wrap!] (rf/use-state (boolean (:wrap? options)))
        [folded? set-folded!] (rf/use-state (boolean (:folded? options)))
        [copy-label copy!] (use-copy-feedback code)
        *element (use-copy-gesture copy!)
        inline? (:inline? options)
        line-count (rf/use-memo
                     #(count (string/split code #"\n" -1))
                     #js [code])
        fold-lines (get options :fold-lines 8)
        foldable? (and (:foldable? options) (> line-count fold-lines))
        copy-button [:button.code-copy
                     {:type "button"
                      :title copy-label
                      :aria-label (if (= copy-label "Copy") "Copy code" copy-label)
                      :on-click (fn [event]
                                  (.preventDefault event)
                                  (.stopPropagation event)
                                  (copy!))}
                     (if inline?
                       [:span {:aria-hidden true}
                        (case copy-label "Copy" "⧉" "Copied" "✓" "Copying…" "…" "!")]
                       copy-label)]
        feedback [:span.code-copy-status {:role "status" :aria-live "polite"}
                  (when (not= copy-label "Copy") copy-label)]]
    (if inline?
      [:span.code-snippet {:ref *element}
       [<formatter> code options]
       copy-button
       feedback]
      [:div.code-block
       {:ref *element
        :class (string/join " " (cond-> []
                                   wrap? (conj "code-block-wrapped")
                                   (and foldable? folded?) (conj "code-block-folded")))
        :style {"--code-fold-lines" fold-lines}}
       [:div.code-block-controls
        copy-button
        [:button {:type "button"
                  :aria-pressed wrap?
                  :on-click (fn [_] (set-wrap! (not wrap?)))}
         "Wrap lines"]
        (when foldable?
          [:button {:type "button"
                    :aria-expanded (not folded?)
                    :on-click (fn [_] (set-folded! (not folded?)))}
           (if folded? (str "Show all " line-count " lines") "Fold")])]
       [:div.code-block-content [<formatter> code options]]
       feedback])))
