(ns tolgraven.ui
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-graph.core :as rg]
   [clojure.string :as string]
   [markdown.core :refer [md->html]]
   [cljs-time.core :as ct]
   [cljs-time.format :refer [formatters formatter unparse]]))


(defn toggle-for-path "Like below but supports attrs + path. I mean could just check if first arg is map anyways but yeah"
  ([model-path])
  ([attrs model-path]))

(defn toggle "A nice little (but not native checkbox little) toggle"
 ([model-path]
  (let [on-change #(rf/dispatch [:set model-path %])
        model (rf/subscribe (into [:get] model-path))]
  [toggle {} model on-change]))
 ([model on-change]
  (toggle {} model on-change))
 ([attrs model on-change]
  (let []
   [:label.toggle-switch
      attrs
    #_(util/deep-merge {:style {}} attrs)
    [:input {:type :checkbox :default-checked @model
             :on-click (fn [e] ; (.preventDefault e) ;broke it! :O what
                 (on-change (not @model)))}]
    ; [:span.toggle-slider.round]
    ])))

(defn burger "Toggle main menu. Entire point vs css is shove everything below down by extra height..."
 ([model on-change]
  [:label.burger
    {:for "burger-check"}
    [:input#burger-check.burger-check
     {:type :checkbox :default-checked @model
      :on-click (fn [e] ; (.preventDefault e) ;broke it! :O what
                 (on-change (not @model)))}] ]))

(defn float-img "Needs to go within a float-wrapper..."
  [id img-attr & [caption pos]]
  [:figure.float-with-caption {:id id :class (or pos "left")}
      [:img.media.image-inset img-attr]
      (when caption [:figcaption caption])])

(defn auto-layout-text-imgs "Take text and images and space out floats appropriately. Pretty dumb but eh"
  [content]
  (let [text-part (string/split-lines (:text content))
         img-count (count (:images content))
         line-count (count text-part)
         chunk-size (/ line-count img-count)
         content  (interleave (mapv (partial into [float-img])
                                    (:images content))
                              (mapv (partial into [:p])
                                    (partition chunk-size text-part)))]
     [:div.float-wrapper
      [:h3 (:title content)]
      content]))


(defn material-toggle
 [model-path [on-state off-state & [prefix]]]
 (let [model (rf/subscribe (into [:get] model-path))]
  [:i.zmdi
   {:class (str "zmdi-"
                (when prefix (str prefix "-"))
                (if @model on-state off-state))
    :style {:margin "0.1em 0.2em"}
    ; :style {:margin "0.2em 0.4em"}
    :on-click #(db/toggle model-path)}]))

(defn minimize [model-path]
 [material-toggle
  (into model-path [:minimized])
  ["maximize" "minimize" "window"]])

(defn close [on-click]
 [:i.zmdi.zmdi-close
  {:style {:position :absolute
           ;:right "0.4rem" :top "0.2rem"
           :right -5 :top -5
           :background "rgb(30, 30, 30, 0.7)"
           :color "#edc"
           :border-radius "50%"
           :padding "0.1em"
           :font-size "1.0rem"
           ; :line-height "1em"
           }
   :on-click on-click}])

(defn formatted-data [title path-or-data]
 (let [data (if (vector? path-or-data)
             @(rf/subscribe path-or-data)
             path-or-data)]
  [:div {:style {:text-align :left}}
  [:h5 title]
  [:pre (pprint/write data :stream nil)]]))


(defn input-text "Returns markup for a basic text input label"
 [& {:as args :keys [value path on-enter]}]
 (let [external-model (r/atom (or (rf/subscribe path) (at value))) ;ok so why does (sub in ratom...) work, straight subscribe not...
       internal-model (r/atom (if (nil? @external-model) "" @external-model))] ;; Create a new atom from the model to be used internally (avoid nil)
  (fn [& {:as args :keys [value path on-enter on-change placeholder width height change-on-blur? disabled? class style attr]}] ;to pass through from outer
   (let [latest-ext-model (or @(rf/subscribe path) (at value)) ;how repl this if not passing model but sub?
         disabled?        (at disabled?)
         change-on-blur?  (at change-on-blur?)]
    (when (not= @external-model latest-ext-model) ;; Has model changed externally?
     (reset! external-model latest-ext-model)
     (reset! internal-model latest-ext-model))
    [:input.form-control
     (merge {:class class, :type "search" ;for clear button ;"text"
             :style (merge {:display "inline-flex" :flex "1 1 auto"
                            :width (or width 100) ; how do like "min-width 'chars in str model + 10' up til 200 pixels yada?"
                            :height height}       ; user best wrap in div or pass class for more fine grained control either way
                           style)
             :placeholder placeholder
             :value       @internal-model
             :disabled    disabled?
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


(defn log "Show an expandable log thingy. Prob dumb here but good base for any sorta feed thingy I guess!" []
 (let [options (rf/subscribe [:get :options :display :log])
       diag    (rf/subscribe [:get :diagnostics])
       time-format (formatters :hour-minute-second)
       table-ref (atom nil) ; scroll (r/atom nil)
       line (fn [{:keys [time level title message] :as msg}]
             [:tr.log-message
              [:td (unparse time-format time)]
              [:td {:class (str "message " (name level))} (name level)]
              [:td title]
              [:td.message #_{:style {:position :relative :left "1em"}} (str message)]])]
  (r/create-class
   {:display-name "Log"
    :component-did-update (fn [this] ; (r/dom-node this)
                           ; (println "Log updated!" (.-scrollHeight @table-ref))
                           ; (reset! scroll (.-scrollHeight @table-ref))
                           (set! (.-scrollTop @table-ref) (.-scrollHeight @table-ref))) ;resort to this since :scroll-top @ratom in the actual element doesnt work...
    :reagent-render
    (fn []
     [:div.log-container
      [minimize [:options :display :log]] ;this also needs to send an event to scroll-top the fucker...
      [:div.log-inner {:ref (fn [el] (reset! table-ref el))
                       :style {:max-height (if (:minimized @options) "1.2rem" "20em")}
                       ; :scroll-top @scroll ;wonder why this doesnt work
                       #_:style #_{:max-height @scroll}}
       [:table>tbody.log
        (for [msg (map (:messages @diag)
                       (sort (keys (:messages @diag)))
                       #_(if (:minimized @options)
                                          [(count (:messages @diag))]
                                          (sort (keys (:messages @diag)))))]
         ^{:key (str (:id msg))}
         [line msg])]]])})))

(defn modal "Container for anything modal, taking care of common stuff. USE FOR COOKIE NOTICE LOL YES"
 [component & [on-outside-click]]
 (let []
  (rf/dispatch [:modal true])
  [:div#modal-container
   [:div#modal-bg {:on-click on-outside-click
                   :style {:position :fixed
                           :width "100%" :height "100%" :top 0 :left 0
                           :background "rgb(30, 30, 30, 0.5)"}}]
   [:div#modal {:class (when @(rf/subscribe [:modal]) "modal-is-open")}
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

(defn hud "Render a HUD sorta like figwheel's but at reagent/re-frame level" []
 (let [to-show @(rf/subscribe [:hud])
       one-msg (fn [{:keys [level title message time actions id]}]
                (let [class (str "hud-message " (name level))]
                 [:div.hud-message
                  {:class class
                   :style {:position :relative}
                   :on-click #(rf/dispatch (or (:on-click actions)
                                               [:hud :modal id])) }
                  [:span title]
                  [close (fn [e]
                             (.stopPropagation e) ;it's causing a click on hud-message as well...
                             (rf/dispatch [:diag/unhandled :remove id]))]]))]
  [:div.hud.hidden
   {:class (when (seq to-show) "visible")}
   (for [msg to-show]
     [one-msg msg])]))
   ; [ui/flip-move
   ;  {:class "hud-messages"
   ;   :duration 200 :staggerDelayBy 20 :staggerDurationBy 30}
   ;  (for [msg to-show]
   ;   [one-msg msg])]]))




