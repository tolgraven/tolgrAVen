(ns website.modules.highlight.code-block
  "Controls hydrate immediately; the formatter hydrates after the page is ready."
  (:require [clojure.string :as string]
            [website.schema.dsl :as dsl]
            [website.component.registry]
            [website.component.restore :as restore]
            [website.component.hydration :as hydration]
            [website.component.font :as font]
            [website.browser-resources :as resources]
            [website.react :as r]
            [website.components.select :as select]
            [website.components.ui.action :as action]
            [website.content.schema :as content-schema]
            [website.macros :refer-macros [defc]]
            [website.render-context :as context]
            [website.loader :as loader]
            [website.listener :as listener]
            [website.loader.view :as view]))

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
   [:?line-prefix fn?]
   [:?starting-line-number [:int {:min 1}]]
   [:?foldable? :boolean]
   [:?folded? :boolean]
   [:?fold-lines [:int {:min 1}]]
   [:?editable? :boolean]
   [:?code-ref [:or fn? [:fn object?]]]
   [:?on-code-focus fn?]
   [:?on-code-change fn?]
   [:?on-language-change fn?]
   [:?language-options content-schema/code-languages]
   [:?on-meta-change fn?]
   [:?meta :?string]])

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

(defn editable-code
  "Normalize native textarea line endings and remove control/display artifacts.
  Keep tabs, newlines and meaningful Unicode, including combining marks and
  joiners used by natural-language text and emoji. Read-only source stays intact."
  [code]
  (-> code
      (string/replace #"\r\n?" "\n")
      (string/replace #"[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u00ad\u061c\u200b\u200e\u200f\u202a-\u202e\u2060\u2066-\u2069\ufeff]" "")))

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
                            (let [component (get-in spec [:view :formatted-code])
                                  component (if (var? component) @component component)]
                              #js {:default (r/reactify-component
                                              (fn [{:keys [args]}]
                                                [<formatted> component args committed!]))}))))))}))

(defc <formatter> [code options on-ready!]
  (let [inherited-hydration? (r/use-context restore/hydrating-context)
        [defer? _] (r/use-state #(and (not context/*server?*)
                                     (or inherited-hydration? (restore/initial-hydration?))))
        committed! (r/use-callback on-ready! [on-ready!])
        [formatter _] (r/use-state #(make-formatter committed!))
        args (into [code] (mapcat identity options))
        lines (string/split code #"\n" -1)
        fallback (if (:inline? options)
                   [:code.code-highlight code]
                   [:pre
                    (if-let [prefix (:line-prefix options)]
                      (into [:code]
                            (map-indexed
                              (fn [index text]
                                (let [number (+ (or (:starting-line-number options) 1) index)]
                                  ^{:key index}
                                  [:span (when-let [props (:line-props options)] (props number))
                                   (prefix number) text
                                   (when (< index (dec (count lines))) "\n")]))
                              lines))
                      [:code code])])
        content (hydration/use-boundary
                  defer?
                  #(r/as-element
                     [r/suspense {:fallback (r/as-element fallback)}
                      (if context/*server?*
                        (view/form :highlight :formatted-code args fallback)
                        ;; Keep Clojure arguments opaque at this React boundary.
                        (r/create-element (:view formatter) #js {:args args}))])
                  [context/*server?* args fallback formatter])]
    (r/use-effect
      (fn []
        (if defer?
          (resources/after-page! (:release! formatter))
          ((:release! formatter))))
      [])
    content))

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

(defc ^:private <editing-controls> [options focus-code!]
  :let [*active (r/atom nil)
        *return-focus? (r/atom false)]
  (let [close! #(reset! *active nil)
        finish! (fn [] (close!) (reset! *return-focus? true))
        blur! (fn [event]
                ;; Browser keyboard handlers may blur an input before its
                ;; Escape event reaches React. Keep focus in code when no
                ;; other element received it; respect actual outside focus.
                (if (and (nil? (.-relatedTarget event)) (.hasFocus js/document))
                  (finish!) (close!)))
        toggle! (fn [field] (if (= field @*active) (finish!) (reset! *active field)))]
    ;; Restore focus after React removes the active popover input. Focusing it
    ;; during keydown can be undone by the commit's native focus restoration.
    (r/use-effect
      (fn []
        (when (and (nil? @*active) @*return-focus?)
          (focus-code!)
          (reset! *return-focus? false))
        nil)
      [@*active @*return-focus? focus-code!])
    [:div.code-edit-controls
     {:content-editable false
      :on-key-down #(.stopPropagation %)}
     [:button.code-language.blog-btn.noborder
      {:type "button"
       :title (str "Language: " (or (not-empty (:language options)) "Auto detect"))
       :aria-label "Choose code language"
       :aria-expanded (= :language @*active)
       :on-pointer-down #(.preventDefault %)
       :on-click #(toggle! :language)}
      [:span {:aria-hidden true} "λ"]]
     [:button.code-metadata.blog-btn.noborder
      {:type "button"
       :title "Fence metadata (optional text after the language)"
       :aria-label "Edit fence metadata"
       :aria-expanded (= :meta @*active)
       :on-pointer-down #(.preventDefault %)
       :on-click #(toggle! :meta)}
      [:span {:aria-hidden true} "⋯"]]
     (case @*active
       :language
       [:div.code-edit-popover
        [select/<search-select>
         {:label "Code language"
          :auto-focus? true
          :on-close (fn [reason event] (if (= :escape reason) (finish!) (blur! event)))
          :value (or (:language options) "")
          :allow-custom? true
          :options (into [{:value "", :label "Auto detect"}]
                         (remove #(empty? (:value %)))
                         (if (contains? options :language-options)
                           (:language-options options)
                           (or @(r/subscribe [:content [:common :code-languages]])
                               [{:value "clojure", :label "Clojure"}
                                {:value "cljs", :label "ClojureScript"}
                                {:value "javascript", :label "JavaScript"}])))
          :on-change (fn [language]
                       (when-let [change! (:on-language-change options)] (change! language))
                       (finish!))}]]
       :meta
       [:label.code-edit-popover
        "Fence metadata"
        [:input {:type "text"
                 :auto-focus true
                 :aria-label "Fence metadata"
                 :placeholder "Optional text after the language"
                 :title "Preserved in Markdown; no display options are currently interpreted."
                 :value (or (:meta options) "")
                 :on-blur blur!
                 :on-key-down (fn [event]
                                (when (#{"Enter" "Escape"} (.-key event))
                                  (.preventDefault event)
                                  (finish!)))
                 :on-change #(when-let [change! (:on-meta-change options)]
                               (change! (.. % -target -value)))}]]
       nil)]))

(defc <code-block>
  {:authoring {:category "Markdown"
               :defaults {:args ["(+ 1 2)" {:language "clojure"}]}}
   :args-schema args-schema
   ;; Editing has two text surfaces; one font owner keeps their metrics and
   ;; deferred face upgrade identical. Read-only blocks keep the pre's owner.
   :features [[:font (fn [_ & options]
                      (when (:editable? (options-map options))
                        (assoc font/fira :fade? false)))]]
   :module :highlight
   :icons ["solid/copy" "solid/check" "solid/ellipsis-h" "solid/exclamation"
           "solid/align-left" "solid/angle-down" "solid/angle-up"]}
  [source & options]
  (let [inherited-hydration? (r/use-context restore/hydrating-context)
        options (options-map options)
        code (if (:editable? options) (editable-code source) source)
        [wrap? set-wrap!] (r/use-state (boolean (:wrap? options)))
        [folded? set-folded!] (r/use-state (boolean (:folded? options)))
        *input (r/use-ref nil)
        [scroll set-scroll!] (r/use-state [0 0])
        focus-code! (r/use-callback #(some-> (.-current *input) .focus) [])
        [copy-label copy!] (use-copy-feedback code)
        [ssr-pending? set-ssr-pending!]
        (r/use-state #(and (not context/*server?*)
                          (or inherited-hydration? (restore/initial-hydration?))))
        on-ready! (r/use-callback #(set-ssr-pending! false) [])
        gesture-props (use-copy-gesture (when (and (not= false (:copy? options))
                                                 (not (:editable? options))) copy!) ssr-pending?)
        inline? (:inline? options)
        line-count (r/use-memo
                     #(count (string/split code #"\n" -1))
                     [code])
        numbered? (if (nil? (:line-numbers? options))
                    (false? (:basic? options)) (:line-numbers? options))
        gutter (if numbered?
                 (str "calc(" (count (str (+ (or (:starting-line-number options) 1) (dec line-count))))
                      "ch + 1.5em + 0.5 * var(--space) + var(--line-width-sm))")
                 "0rem")
        fold-lines (get options :fold-lines 8)
        foldable? (and (:foldable? options) (> line-count fold-lines))
        copy-button [action/<action>
                     {:class "code-copy blog-btn noborder"
                      :label (if (= copy-label "Copy") "Copy code" copy-label)
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
                                   (:editable? options) (conj "code-block-editable")
                                   (and foldable? folded?) (conj "code-block-folded")))
        :tab-index 0
        :style {"--code-fold-lines" fold-lines})
       [:div.code-block-controls
        (when (:editable? options) [<editing-controls> options focus-code!])
        (when (not= false (:copy? options)) copy-button)
        [action/<action>
         {:class "code-wrap blog-btn noborder"
          :label "Wrap lines"
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
       [:div.code-block-content
        {:content-editable (when (:editable? options) false)
         :on-key-down (when (:editable? options) #(.stopPropagation %))
         :style (when (:editable? options)
                  {"--code-scroll-left" (str (first scroll) "px")
                   "--code-scroll-top" (str (second scroll) "px")
                   "--code-gutter" gutter})}
        (if (:editable? options)
          [:div.code-block-highlight {:aria-hidden true}
           [<formatter> code options on-ready!]]
          [<formatter> code options on-ready!])
        (when (:editable? options)
          [:textarea.code-block-input
           {:aria-label "Code"
            :value code
            :ref (fn [input]
                   (set! (.-current *input) input)
                   (when-let [ref (:code-ref options)]
                     (if (fn? ref) (ref input) (set! (.-current ref) input))))
            :on-focus (:on-code-focus options)
            :spell-check false
            :auto-capitalize "off"
            :auto-correct "off"
            :wrap (if wrap? "soft" "off")
            :on-scroll #(set-scroll! [(.. % -target -scrollLeft) (.. % -target -scrollTop)])
            :on-change #(when-let [change! (:on-code-change options)]
                          (change! (editable-code (.. % -target -value))))}])]
       feedback])))
