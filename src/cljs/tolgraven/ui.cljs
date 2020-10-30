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

(defn md->div [md]
  [:div {:dangerouslySetInnerHTML {:__html (md->html md)}}])

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
  [:figure.float-with-caption {:id id :class (or pos "left")}
      [:img.media.image-inset img-attr]
      (when caption [:figcaption caption])])

(defn auto-layout-text-imgs "Take text and images and space out floats appropriately. Pretty dumb but eh"
  [content]
  (let [text-part (for [line (string/split-lines (:text content))]
                    [:<> [:span line] [:br]])
         chunk-size (int (/ (count text-part)
                            (count (:images content))))
         result (->> (util/interleave-all (map #(into [float-img] %)
                                               (:images content))
                                          (map #(into [:p] %)
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
   (for [msg @to-show]
     [msg-fn msg])]))



(def trans-group (r/adapt-react-class rtg/TransitionGroup))
(def css-trans (r/adapt-react-class rtg/CSSTransition))
