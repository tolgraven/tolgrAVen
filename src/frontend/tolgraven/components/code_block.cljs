(ns tolgraven.components.code-block
  "Controls hydrate immediately; the formatter hydrates after the page is ready."
  (:require [clojure.string :as string]
            [tolgraven.schema.dsl :as dsl]
            [tolgraven.component.registry]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.hydration :as hydration]
            [tolgraven.browser-resources :as resources]
            [tolgraven.react :as r]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.render-context :as context]
            [tolgraven.loader :as loader]
            [tolgraven.listener :as listener]
            [tolgraven.loader.view :as view]))

(def options-schema
  [:map
   [:?language :?string]
   [:?default-language :?string]
   [:?auto-languages [:?vector :string]]
   [:?inline-language :?string]
   [:?style [:or :map [:fn object?]]]
   [:?basic? :boolean]
   [:?inline? :boolean]
   [:?copy? :boolean]
   [:?wrap? :boolean]
   [:?line-numbers? :boolean]
   [:?line-props fn?]
   [:?starting-line-number [:int {:min 1}]]
   [:?foldable? :boolean]
   [:?folded? :boolean]
   [:?fold-lines [:int {:min 1}]]])

;; Preserve the public keyword/value API, with the same contracts as map options.
(def args-schema
  [:cat :string
   [:alt
    [:cat options-schema]
    [:* (into [:alt]
          (map (fn [[key _ schema]] [:cat [:= key] schema])
               (rest (dsl/->malli options-schema))))]]])

(defn options-map [options]
  (if (and (= 1 (count options)) (map? (first options)))
    (first options)
    (apply hash-map options)))

(defc <formatted> [component args committed!]
  (r/use-layout-effect (fn [] (committed!)) [])
  (into [component] args))

(defn- make-formatter [committed!]
  ;; Construction is pure. The owned effect releases this gate; only then can
  ;; React.lazy acquire code through the existing parallel CSS/JS loader.
  (let [*release (atom nil)
        gate (js/Promise. (fn [resolve _] (reset! *release resolve)))]
    {:release! #(@*release nil)
     :view (r/lazy
             (fn []
               (-> gate
                   (.then (fn [_] (loader/acquire-code! :highlight)))
                   (.then (fn [spec]
                            (let [component (get-in spec [:view :code-block])
                                  component (if (var? component) @component component)]
                              #js {:default (r/reactify-component
                                              (fn [{:keys [args]}]
                                                [<formatted> component args committed!]))}))))))}))

(defc <formatter> [code options on-ready!]
  (let [[defer? _] (r/use-state #(and (not context/*server?*) (restore/initial-hydration?)))
        release-hydration! (hydration/use-deferred! defer?)
        committed! (r/use-callback
                     (fn [] (release-hydration!) (on-ready!))
                     [release-hydration! on-ready!])
        [formatter _] (r/use-state #(make-formatter committed!))
        args (into [code] (mapcat identity options))
        fallback (if (:inline? options) [:code.code-highlight code] [:pre [:code code]])]
    (r/use-effect
      (fn []
        (if defer?
          (resources/after-page! (:release! formatter))
          ((:release! formatter))))
      [])
    ;; Reagent defc memoizes this boundary independently of outer controls.
    [r/suspense {:fallback (r/as-element fallback)}
     (if context/*server?*
       (view/form :highlight :code-block args fallback)
       ;; Keep Clojure arguments opaque at this native React boundary.
       (r/create-element (:view formatter) #js {:args args}))]))

(r/reg-event-fx :code-block/copy
  {:args [:cat :string fn?]}
  (fn [_ [_ text complete!]]
    {:code-block/copy {:text text
                       :complete! complete!}}))
(r/reg-fx :code-block/copy
  (fn [{:keys [text complete!]}]
    (if-let [clipboard (when (exists? js/navigator) (.-clipboard js/navigator))]
      (-> (.writeText clipboard text)
          (.then #(complete! "Copied"))
          (.catch #(complete! "Copy failed")))
      (complete! "Copy unavailable"))))

(defn- use-copy-feedback [code]
  (let [[label set-label!] (r/use-state "Copy")
        *owner (r/use-ref nil)]
    (r/use-effect
      (fn []
        (set! (.-current *owner) {:token (gensym "copy") :request 0 :timer nil})
        (set-label! "Copy")
        (fn []
          (js/clearTimeout (:timer (.-current *owner)))
          (set! (.-current *owner) nil)))
      [code])
    [label
     (r/use-callback
       (fn []
         (when-let [{:keys [token request timer] :as owner} (.-current *owner)]
           (let [request (inc request)]
             (js/clearTimeout timer)
             (set! (.-current *owner) (assoc owner :request request :timer nil))
             (set-label! "Copying…")
             (r/dispatch
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
       [code])]))

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

(defn- use-copy-gesture [copy! ssr-pending?]
  (let [*element (r/use-ref nil)
        *press (r/use-ref nil)
        cancel! (r/use-callback
                  (fn []
                    (js/clearTimeout (:timer (.-current *press)))
                    (set! (.-current *press) nil))
                  [])
        handlers
        (r/use-memo
          (fn []
            {:on-pointer-down
             (fn [event]
               (cancel!)
               (when (and (.contains (.-current *element) (.-target event))
                          (.-isPrimary event)
                          (zero? (.-button event))
                          (not (control-target? (.-target event))))
                 (set! (.-current *press) {:id (.-pointerId event)
                                          :x (.-clientX event)
                                          :y (.-clientY event)})))
             :on-pointer-move
             (fn [event]
               (when-let [{:keys [id x y released?]} (.-current *press)]
                 (when (and (not released?)
                            (= id (.-pointerId event))
                            ;; Pointer coordinates use the browser's CSS-pixel
                            ;; measurement API; this is not a style dimension.
                            (or (> (js/Math.hypot (- x (.-clientX event))
                                                 (- y (.-clientY event))) 4)
                                (not (inside? (.-current *element)
                                              (.-clientX event) (.-clientY event)))))
                   (cancel!))))
             :on-pointer-leave
             (fn [event]
               (when-let [{:keys [released?]} (.-current *press)]
                 (let [target (.-relatedTarget event)]
                   (when (and (not released?)
                              (not (and (some-> target .-nodeType)
                                        (.contains (.-current *element) target))))
                     (cancel!)))))
             :on-pointer-cancel (fn [_] (cancel!))
             :on-double-click (fn [_] (cancel!))
             :on-select (fn [_] (when (selected? (.-current *element)) (cancel!)))
             :on-pointer-up
             (fn [event]
               (when-let [{:keys [id x y released?] :as press} (.-current *press)]
                 (when (and (= id (.-pointerId event)) (not released?))
                   (if (and (not (control-target? (.-target event)))
                            (<= (js/Math.hypot (- x (.-clientX event))
                                              (- y (.-clientY event))) 4)
                            (inside? (.-current *element) (.-clientX event) (.-clientY event))
                            (not (selected? (.-current *element))))
                     ;; A local, cancellable gesture window distinguishes a tap
                     ;; from selection. The actual clipboard write is an rf effect.
                     (set! (.-current *press)
                       (assoc press :released? true
                              :timer (js/setTimeout
                                       (fn []
                                         (cancel!)
                                         (when-not (selected? (.-current *element)) (copy!)))
                                       500)))
                     (cancel!)))))})
          [copy! cancel!])]
    (r/use-effect (fn [] cancel!) [copy! cancel!])
    (r/use-effect
      (fn []
        (when (and copy! ssr-pending?)
          ;; React holds events targeting an unhydrated Suspense child. Only
          ;; during that initial interval, bridge native intent to the same
          ;; Reagent handlers. Stop replay so a handled tap cannot copy twice.
          (let [token (gensym "ssr-code-pointer")
                events {:on-pointer-down "pointerdown"
                        :on-pointer-move "pointermove"
                        :on-pointer-leave "pointerout"
                        :on-pointer-up "pointerup"
                        :on-pointer-cancel "pointercancel"
                        :on-double-click "dblclick"
                        :on-select "selectionchange"}]
            (doseq [[attribute event] events]
              (listener/register!
                {:id [token attribute]
                 :owner :code-block
                 :target js/document
                 :event event
                 :capture? true
                 :handler (fn [event]
                            (let [element (.-current *element)
                                  inside? (.contains element (.-target event))]
                              (when (or inside? (.-current *press))
                                (when (and inside? (not (control-target? (.-target event))))
                                  (.stopPropagation event))
                                ((handlers attribute) event))))}))
            (fn []
              (doseq [attribute (keys events)] (listener/remove! [token attribute]))))))
      [copy! ssr-pending? handlers])
    (cond-> {:ref *element}
      copy! (merge handlers))))

(defc <code-block>
  {:args-schema args-schema
   :module :markdown
   :icons ["solid/copy" "solid/check" "solid/ellipsis-h" "solid/exclamation"
           "solid/align-left" "solid/angle-down" "solid/angle-up"]}
  [code & options]
  (let [options (options-map options)
        [wrap? set-wrap!] (r/use-state (boolean (:wrap? options)))
        [folded? set-folded!] (r/use-state (boolean (:folded? options)))
        [copy-label copy!] (use-copy-feedback code)
        [ssr-pending? set-ssr-pending!]
        (r/use-state #(and (not context/*server?*) (restore/initial-hydration?)))
        on-ready! (r/use-callback #(set-ssr-pending! false) [])
        gesture-props (use-copy-gesture (when (not= false (:copy? options)) copy!) ssr-pending?)
        inline? (:inline? options)
        line-count (r/use-memo
                     #(count (string/split code #"\n" -1))
                     [code])
        fold-lines (get options :fold-lines 8)
        foldable? (and (:foldable? options) (> line-count fold-lines))
        copy-button [:button.code-copy.blog-btn.noborder
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
                       [:i.fas {:aria-hidden true
                                :class (case copy-label
                                         "Copy" "fa-copy"
                                         "Copied" "fa-check"
                                         "Copying…" "fa-ellipsis-h"
                                         "fa-exclamation")}])]
        feedback [:span.code-copy-status {:role "status" :aria-live "polite"}
                  (when (not= copy-label "Copy") copy-label)]]
    (if inline?
      [:span.code-snippet gesture-props
       [<formatter> code options on-ready!]
       (when (not= false (:copy? options)) copy-button)
       feedback]
      [:div.code-block
       (assoc gesture-props
        :class (string/join " " (cond-> []
                                   wrap? (conj "code-block-wrapped")
                                   (and foldable? folded?) (conj "code-block-folded")))
        :tab-index 0
        :style {"--code-fold-lines" fold-lines})
       [:div.code-block-controls
        (when (not= false (:copy? options)) copy-button)
        [:button.code-wrap.blog-btn.noborder
         {:type "button"
          :title "Wrap lines"
          :aria-label "Wrap lines"
          :aria-pressed wrap?
          :on-click (fn [_] (set-wrap! (not wrap?)))}
         [:i.fas.fa-align-left {:aria-hidden true}]]
        (when foldable?
          [:button.code-fold.blog-btn.noborder
           {:type "button"
            :title (if folded? (str "Show all " line-count " lines") "Fold")
            :aria-label (if folded? (str "Show all " line-count " lines") "Fold")
            :aria-expanded (not folded?)
            :on-click (fn [_] (set-folded! (not folded?)))}
           [:i.fas {:aria-hidden true
                    :class (if folded? "fa-angle-down" "fa-angle-up")}]])]
       [:div.code-block-content [<formatter> code options on-ready!]]
       feedback])))
