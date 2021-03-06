(ns tolgraven.ui
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [tolgraven.db :as db]
   [tolgraven.util :as util :refer [at]]
   [clojure.string :as string]
   [clojure.pprint :as pprint]
   [markdown.core :refer [md->html]]
   [highlight.js :as highlight.js] ; can auto-run on hook instead of tagging manually...
   [cljsjs.highlight.langs.clojure] ; needs to be included somewhere
   [react-transition-group :as rtg]
   [cljs-time.core :as ct]
   [cljs-time.coerce :as ctc]
   [cljs-time.format :as ctf]))


(defn safe "Error boundary for components. Also prints/logs error"
  [category & children]
 (let [exception (rf/subscribe [:exception [category]])] ;or reg not ratom or would cause etra re-render?
  (r/create-class
  {:display-name (str "Boundary: " (name category))
   :component-did-catch (fn [error info] ;apparently no :this oi!
                          (util/log :error "Component" (pr-str info))
                          (rf/dispatch [:exception [category]
                                        {:error error :info info}])) ; error and info are empty.
   ; :get-derived-state-from-error ;"defined as instance method and will be ignored. define as static"
   ; (fn [error] ;this should update state to serve like, an error page (for render) "if using getDerivedState methods, the state has to be plain JS object as React implementation uses Object.assign to merge partial state into the current state."
   ;  (rf/dispatch [:exception [category]
   ;                {:error error :info (:info exception)}])
   ;  #js {}) ; empty new state since not using react state
    ; ^ found above in some blog post, just makes it go blank on error...
   :reagent-render
   (fn [category component]
    (if-not @exception   ;state change downstream? then it gets easier to debug "in-page",
     component
     (let [[component state] component] ;cant remember why this is
        [:section.component-failed
          [:p "Component exception"]
          [:pre (-> @exception :info pr-str pprint/pprint)]
          [:pre (-> @exception :error pr-str pprint/pprint)]
          [:div
           [:button {:on-click #(rf/dispatch [:state [:exception category] nil])}
            "Attempt reload"]]])))})))


(defn md->div [md]
  [:div.md-rendered
   {:dangerouslySetInnerHTML {:__html (md->html md)}}])

(defn appear "Animate mount"
  [id kind & components]
  [:div.appear-wrapper
   {:id id
    :class (str kind " "
                (when @(rf/subscribe [:state [:appear id]]) "appeared"))
    :ref #(rf/dispatch [:appear id (boolean %)])}
   (into [:<>] components)])

(defn appear-anon "Animate mount. Dont use events just ratoms."
  [kind & components]
  (let [appeared (r/atom false)]
    (fn [kind & components]
      [:div.appear-wrapper
       {:class (str kind " " (when @appeared "appeared"))
        :ref #(when % (reset! appeared true))}
       (into [:<>] components)])))

(defn seen "Animate on coming into view"
  [id kind & components]
  (let [on-change (fn [frac]
                    (let [state @(rf/subscribe [:state [:seen id]])]
                      (cond
                       (and (>= frac 0.50) (not state))
                       (rf/dispatch [:state [:seen id] true])
                       (and (< frac 0.50) state)
                       (rf/dispatch [:state [:seen id] false]))))
        observer (util/observer on-change (str "seen-" id))]
    (fn [id kind & components]
      [:div.appear-wrapper
       {:id id
        :class (str kind " "
                    (when @(rf/subscribe [:state [:seen id]])
                      "appeared"))
        :ref #(observer %)}
       (into [:<>] components)])))

(defn seen-anon "Animate on coming into view"
  [kind & components]
  (let [seen (r/atom false)
        on-change (fn [frac]
                    (cond
                       (and (>= frac 0.50) (not @seen))
                       (reset! seen true)
                       (and (< frac 0.50) @seen)
                       (reset! seen false)))
        observer (util/observer on-change)]
    (fn [kind & components]
      [:div.appear-wrapper
       {:class (str kind " " (when @seen "appeared"))
        :ref #(observer %)}
       (into [:<>] components)])))

(defn seen-2 "Animate on coming into view. takes a map"
  [id kind div & components]
  (let [on-change (fn [frac]
                    (let [state @(rf/subscribe [:state [:seen id]])]
                      (cond
                       (and (>= frac 0.50) (not state))
                       (rf/dispatch [:state [:seen id] true])
                       (and (< frac 0.50) state)
                       (rf/dispatch [:state [:seen id] false]))))
        observer (util/observer on-change (str "seen-" id))]
    (fn [id kind & components]
      (into div
            [{:id id
              :class (str "appear-wrapper "
                          kind " "
                          (when @(rf/subscribe [:state [:seen id]])
                            "appeared"))
              :ref #(observer %)}
             (into [:<>] components)]))))

(defn zoom-to-modal "E.g. bring up image to semi-fullscreen when clicked.
                     This version just generically zooms, would be nice if animated from current pos.
                     Would need to grab actual pos and size from DOM for that..."
  ; TODO should also function as gallery if passing multiple elements
  ; TODO stop scroll events while active
  [id]
  (when-let [component @(rf/subscribe [:state [:modal-zoom id :component]])]
    (let [opened (and @(rf/subscribe [:state [:modal-zoom id :opened]])
                      @(rf/subscribe [:state [:modal-zoom id :loaded]]))
          on-click #(rf/dispatch [:modal-zoom id :close])] ;actually guess container will handle click on img too. but later if add buttons etc, nyah
      [:div.modal-bg.center-content
       {:class (when opened "modal-bg-open")
        :on-click on-click }
       [:div#modal.modal-zoom.center-content
        {:class (when opened "modal-zoomed")
         :on-click on-click
         :ref #(when % (rf/dispatch [:modal-zoom id :loaded])) } ;to apply things only later
        (util/add-attrs component
                        {:class "modal-zoomed-item"}) ]])))

(defn inset [caption nr]
  (let [pos (case (mod nr 4)
              0 "bottom right"  1 "bottom left"  2 "top right"   3 "top left")]
    [:p.caption-inset {:class pos}
     caption]))


(defn user-avatar "Display a user avatar, with common fallbacks"
  [user-map & [extra-class]]
  (let [fallback @(rf/subscribe [:user/default-avatar])
        error? (r/atom false)]
    (fn [user-map & [extra-class]]
      [:div.user-avatar-container ; wrapping in div causes stretch bs not to occur, somehow makes img respect its given w/h
       [:img.user-avatar
        {:class extra-class
         :src (if @error? fallback (or (:avatar user-map) fallback))
         :on-error #(reset! error? true)
         :alt (str (:name user-map) " profile picture")} ]])))


(defn fading-bg-heading [{:keys [title target bg tint] :as content}]
  [:<>
   [:div.fading-bg-heading
    {:class "section-with-media-bg-wrapper covering stick-up fullwidth"
     :on-click (when target #(rf/dispatch [:common/navigate! target]))}
    [:div.fader
     [:img.media.media-as-bg bg]
     [:section.covering-faded.noborder
      {:style (when tint {:background (str "var(--" tint ")")
                          :filter "saturate(1.7) brightness(0.9)"})}
      [:h1.h-responsive
       {:style {:transform "translateY(-10%)"}}
       title]]]]
   [:div.fader>div.fade-to-black.bottom]])


(defn button "Pass text and id, plus either link anchor or action..."
  [text id & {:keys [type bg-div-class link action disabled?]
              :or   {type "button" }}]
  [:button {:id (str "btn-" (name id)) :type type :on-click action :disabled disabled?}
   (when bg-div-class [:div {:class bg-div-class}])
   [:label {:for (str "btn-" (name id))}
    (if link
      [:a {:href link} text]
      text)]])


(defn toggle-for-path "Like below but supports attrs + path. I mean could just check if first arg is map anyways but yeah"
  ([model-path])
  ([attrs model-path]))

(defn toggle "A nice little (but not native checkbox little) toggle"
 ([model-path label]
  (let [on-change #(rf/dispatch [:set model-path %])
        model (rf/subscribe (into [:get] model-path))]
  [toggle {} model on-change label]))
 ([model on-change label]
  (toggle {} model on-change label))
 ([attrs model on-change label]
  (let []
   [:label.toggle-switch
      attrs
    [:input {:type :checkbox :default-checked @model
             :on-click (fn [e] ; (.preventDefault e) ;broke it! :O what
                 (on-change (not @model)))}]
    [:span.toggle-slider]
    [:div.toggle-label label]
    ])))

(defn burger "Toggle main menu. Entire point vs css is shove everything below down by extra height..."
  ([id model on-change]
   [:label.burger
    {:for id}
    [:input.burger-check
     {:id id :type :checkbox
      :default-checked @model
      :on-click (fn [e] ; (.preventDefault e) ;broke it! :O what
                  (on-change (not @model)))}]]))

(defn float-img "Needs to go within a float-wrapper..."
  [id img-attr & [caption pos]]
  (let [zoomed? (r/atom false)]
    (fn [id img-attr & [caption pos]]
      [:figure.float-with-caption
       {:id id :class (or pos "left")
        :style (when @zoomed?
                 {:width "80%" ; TODO nvm not hardcoding and not going crazy large when vw high, should be based on img size so don't blow up too much anyways
                  :margin "var(--space-lg) 10%"}) }
       [seen-anon "zoom" ;"slide-in"
        [:img.media.image-inset
         (merge img-attr
         {:on-click #(r/rswap! zoomed? not)})]]
       (when caption [:figcaption caption])])))

(defn auto-layout-text-imgs "Take text and images and space out floats appropriately. Pretty dumb but eh"
  [content]
  (let [text-part (for [line (string/split-lines (:text content))]
                    [:<>
                     [seen-anon "slide-in"
                      [:span line]]
                     [:br]])
         chunk-size (int (/ (count text-part)
                            (count (:images content))))
         result (->> (util/interleave-all (map #(into [float-img] %)
                                               (:images content))
                                          (map #(into [:div] %)
                                               (partition chunk-size chunk-size
                                                          (repeat "") text-part)))
                      (map-indexed (fn [i v]
                                     (with-meta
                                      v {:key (str "auto-layout-part-" i)}))))] ;would need a parent id thingy as well tho
     [:div.float-wrapper
      result]))


(defn material-toggle
 [model-path [on-state off-state & [prefix]]]
 (let [model (rf/subscribe (into [:get] model-path))]
  [:i.fa
   {:class (str "fa-"
                (when prefix (str prefix "-"))
                (if @model on-state off-state))
    :style {:margin "0.1em 0.2em"}
    :on-click #(rf/dispatch [:toggle model-path])}]))

(defn minimize [model-path]
 [material-toggle
  (into model-path [:minimized])
  ["maximize" "minimize" "window"]])

(defn close [on-click]
 [:button.close-btn.noborder
  [:i.fa.fa-times
   {:on-click on-click}]])

(defn formatted-data [title path-or-data]
 (let [data (if (vector? path-or-data)
             @(rf/subscribe path-or-data)
             path-or-data)]
  [:div {:style {:text-align :left}}
  [:h5 title]
  [:pre (pprint/write data :stream nil)]]))


; was trying to adapt this when still learning from zero using re-com
; none of it makes sense _and_ it's dropping keystrokes anyways haha
; plus need to diverge code for textarea really...
; basically gör om gör rätt
; textarea diffs:
; don't swallow enter (fucks repeat)
; maybe use ext md editor?
; alt-enter shortcut for post + others, re-press is kb shortcut lib

(defn input-text-2 "Returns markup for a basic text input label"
 [& {:as args :keys [value path on-enter]}]
 (let [sub-or-val     #(or @(rf/subscribe path) (at value))
       external-model (r/atom (sub-or-val)) ;ok so why does (sub in ratom...) work, straight subscribe not...
       internal-model (r/atom (if (some? @external-model) @external-model ""))] ;; Create a new atom from the model to be used internally (avoid nil)
  (fn [& {:as args
          :keys [input-type value path on-enter on-change placeholder
                 width height change-on-blur? disabled?
                 class style attr]
          :or {input-type :input.form-control
               width 100}}] ;to pass through from outer

   (let [latest-ext-model (sub-or-val) ;how repl this if not passing model but sub?
         sync-ext         #(rf/dispatch (into path @internal-model))
         disabled?        (at disabled?)
         style            (merge {:display "inline-flex" :flex "1 1 auto"
                                  :width width ; how do like "min-width 'chars in str model + 10' up til 200 pixels yada?"
                                  :height height}
                                 style)
         change-on-blur?  (at change-on-blur?)
         on-blur          (fn [e]
                            (when (and on-change change-on-blur?
                                       (not= @internal-model @external-model))
                              (sync-ext) ; (reset! external-model @internal-model) ;well havoc if is a sub?
                              (on-change @internal-model)))
         on-change-fn (fn [e]
                        (let [new-val (-> e .-target .-value)]
                          (when (and on-change (not disabled?))
                            (reset! internal-model new-val)
                            (when-not change-on-blur?
                              (sync-ext) ; (reset! external-model @internal-model) ;uhh cant do that obviously
                              (on-change @internal-model)))))
         on-key-up (fn [e]
                     (if disabled?
                       (.preventDefault e)
                       (case (.-key e)
                         "Enter" (if change-on-blur?
                                   (do (sync-ext) ;(reset! external-model @internal-model)
                                       (when on-change (on-change @internal-model)))
                                   (when (and on-enter (not= "" @internal-model))
                                     (on-enter @internal-model)))
                         "Escape" (if change-on-blur?
                                    (reset! internal-model @external-model)) ;already loses focus automatically when press esc
                         true)))]
    (when (not= @external-model latest-ext-model) ;; Has model changed externally?
     (reset! external-model latest-ext-model)
     (reset! internal-model latest-ext-model))
    [input-type
     (merge {:class       class, ;:type "search" ;for clear button ;"text"
             :style       style       ; user best wrap in div or pass class for more fine grained control either way
             :placeholder placeholder
             :value       @internal-model
             :disabled    disabled?
             :on-change   on-change-fn
             :on-blur     on-blur
             :on-key-up   on-key-up}
            attr)])))) ;after not before, want to be able to override stuff duh
;
(defn input-text "Returns markup for a basic text input label"
 [& {:as args :keys [value path on-enter]}]
 (let [external-model (r/atom (or (rf/subscribe path) (at value))) ;ok so why does (sub in ratom...) work, straight subscribe not...
       internal-model (r/atom (if (nil? @external-model) "" @external-model)) ;; Create a new atom from the model to be used internally (avoid nil)
       div-ref        (r/atom nil)
       base-scroll-height (atom 0)]
   (fn [& {:keys [value path on-enter on-change placeholder width height min-rows
                  change-on-blur? disabled? class style attr input-type type]
           :or {input-type :input.form-control
               width "15em"}}] ;to pass through from outer
   (let [latest-ext-model (or @(rf/subscribe path) (at value)) ;how repl this if not passing model but sub?
         disabled?        (at disabled?)
         change-on-blur?  (at change-on-blur?)
         ]
    (when (not= @external-model latest-ext-model) ;; Has model changed externally?
     (reset! external-model latest-ext-model)
     (reset! internal-model latest-ext-model))
    [input-type
     (merge {:class class, :type type ;for clear button ;"text"
             :style (merge {:display "inline-flex" :flex "1 1 auto"
                            :width width ; how do like "min-width 'chars in str model + 10' up til 200 pixels yada?"
                            :height height}       ; user best wrap in div or pass class for more fine grained control either way
                           style)
             :placeholder placeholder
             :autoComplete (string/lower-case placeholder)
             :value       @internal-model
             :disabled    disabled?
             :ref         (fn [el]
                            (when el (reset! base-scroll-height (.-scrollHeight el)))
                            (reset! div-ref el))
             :on-change (fn [e]
                         (let [new-val (-> e .-target .-value)]
                          (when (and on-change (not disabled?))
                           (reset! internal-model new-val)
                           (when-not change-on-blur?
                            (reset! external-model @internal-model) ;uhh cant do that obviously
                            (on-change @internal-model)))))
             :on-blur   (fn [e]
                         (when (and on-change change-on-blur?
                                    (not= @internal-model @external-model))
                          (reset! external-model @internal-model) ;nor here...
                          (on-change @internal-model)))
             :on-input  (when (and min-rows (= input-type :textarea) @div-ref) ;try make autoexpand...
                          (fn [e]
                            (let [row-diff (int (/ (- @base-scroll-height
                                                      (.-scrollHeight @div-ref))
                                                   16))] (set! (.-rows @div-ref)
                                  (+ min-rows row-diff)))))
             :on-key-up (fn [e]
                         (if disabled? (.preventDefault e)
                          (case (.-key e)
                           "Enter" (if change-on-blur?
                                    (do (reset! external-model @internal-model)
                                        (when on-change (on-change @internal-model)))
                                    (when (and on-enter (not= "" @internal-model))
                                     (on-enter @internal-model)))
                           "Escape" (if change-on-blur?
                                     (reset! internal-model @external-model)) ;already loses focus automatically when press esc
                           true)))}
            attr)])))) ;after not before, want to be able to override stuff duh


(defn format-log-message
  [message]
  (with-out-str (pprint/pprint message)) ;XXX temp.
  (pprint/write message :stream nil) ;XXX temp.
  ; (cond
  ;   (string? message) message
  ;   (object? message) (-> message js->clj :message str))
  )

(defn log "Show an expandable log thingy. Prob dumb here but good base for any sorta feed thingy I guess!"
  [options content]
 (let [table-ref (atom nil)
       log-line (fn [{:keys [time level title message] :as msg}]
                  [:tr.log-messages
                    [:td.log-time (ctf/unparse (ctf/formatters :hour-minute-second)
                                               (ctc/from-long time))]
                   [:td.log-level {:class (name level)} (name level)]
                   [:td.log-title title]
                   [:td.log-message [:pre (format-log-message message)]]])]
  (r/create-class
   {:display-name "Log"
    :component-did-update (fn [this]
                           (set! (.-scrollTop @table-ref)
                                 (.-scrollHeight @table-ref))) ;resort to this since :scroll-top @ratom in the actual element doesnt work...
    :reagent-render
    (fn []
      (let [messages (:messages @content)]
        [:section#log-container.log-container.solid-bg
         {:ref #(when % (rf/dispatch [:run-highlighter! %]))}
         ; {:ref #(when % (util/run-highlighter! "pre" %))}
         [minimize [:state [:display :log]]] ;this also needs to send an event to scroll-top the fucker...
         [:table ;.lined-container-sm
            {:ref (fn [el]
                    (reset! table-ref el))
             :style {:max-height (if (:minimized @options) "1.2em" "20em")}}
          [:tbody.log
           (for [msg (map messages (sort (keys messages))
                          ; (if (:minimized @options) ;upside-down?
                          ;     [(count messages)]
                          ;     (sort (keys messages)))
                            )]
             ^{:key (str (:id msg))}
             [log-line msg])]]]))})))

(defn modal "Container for anything modal, taking care of common stuff. USE FOR COOKIE NOTICE LOL YES"
 [component & [on-outside-click]] ; and path for when to show...
 (let []
  [:div#modal-container
   {:class (when @(rf/subscribe [:state [:modal]]) "modal-is-open")}
   [:div#modal-bg {:on-click (or on-outside-click
                                 #(rf/dispatch [:state [:modal] false]))}]
   [:div#modal ;{:class (when @(rf/subscribe [:state [:modal]]) "modal-is-open")}
    component]]))

(defn hud-modal "Show more info about a specific HUD message"
 [] ;doesnt really have to be modal but wanted to implement that, so...
 (if-let [msg @(rf/subscribe   [:hud :modal])]
  (let [to-close #(rf/dispatch [:hud :modal :remove])]
   [modal [:div.hud-modal-main
           {:class (str "hud-message " (name (:level msg)))}
           [:h3  (:title   msg)]
           [:p   (str (:message msg))]
           [:p   (str (:time    msg))]
           [close to-close]]
    to-close])
  (rf/dispatch [:modal false]))) ;eww gross

(defn hud "Render a HUD sorta like figwheel's but at reagent/re-frame level"
  [to-show]
 (let [msg-fn (fn [{:keys [level title message time actions id]}]
                ^{:key (str "hud-message-" id)}
                [:div.hud-message
                  {:class (name level)
                   :style {:position :relative}
                   ; :ref #(when % (rf/dispatch [:run-highlighter! %]))
                   :ref #(when % (util/run-highlighter! "pre" %)) ;this works but dispatch not??
                   :on-click #(doall (for [action (or (:on-click actions)
                                                       [[:diag/unhandled :remove id]
                                                        [:common/navigate! :log]])] ;TODO should then find elem by log id and scroll to it
                                       (rf/dispatch action)))}
                  [:h4.hud-message-title title]
                  (when message
                    [:pre (format-log-message message)])
                  [close (fn [e]
                             (.stopPropagation e) ;it's causing a click on hud-message as well...
                             (rf/dispatch [:diag/unhandled :remove id]))]])]
  [:div.hud.hidden
   {:class (when (seq @to-show) "visible")}
   (for [msg @to-show
         :let [id (str "hud-id-" (:id msg))]] ^{:key id}
     [appear id "zoom-x slow"
      [msg-fn msg]])]))



(def trans-group (r/adapt-react-class rtg/TransitionGroup))
(def css-trans (r/adapt-react-class rtg/CSSTransition))
