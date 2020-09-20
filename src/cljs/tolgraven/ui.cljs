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

; (defn log "Show an expandable log thingy. Prob dumb here but good base for any sorta feed thingy I guess!" []
;  (let [options (rf/subscribe [:get :options :display :log])
;        diag    (rf/subscribe [:get :diagnostics])
;        time-format (formatters :hour-minute-second)
;        ; time-format (formatters "HH-mm-ss.SSS")
;        table-ref (atom nil) ; scroll (r/atom nil)
;        line (fn [{:keys [time level title message] :as msg}]
;              [:tr.log-message
;               [:td (unparse time-format time)]
;               [:td {:class (str "message " (name level))} (name level)]
;               [:td title]
;               [:td.message #_{:style {:position :relative :left "1em"}} (str message)]])]
;   (r/create-class
;    {:display-name "Log"
;     :component-did-update (fn [this] ; (r/dom-node this)
;                            ; (println "Log updated!" (.-scrollHeight @table-ref))
;                            ; (reset! scroll (.-scrollHeight @table-ref))
;                            (set! (.-scrollTop @table-ref) (.-scrollHeight @table-ref))) ;resort to this since :scroll-top @ratom in the actual element doesnt work...
;     :reagent-render
;     (fn []
;      [:div.log-container
;       [ui/minimize [:options :display :log]] ;this also needs to send an event to scroll-top the fucker...
;       [:div.log-inner {:ref (fn [el] (reset! table-ref el))
;                        :style {:max-height (if (:minimized @options) "1.2rem" "20em")}
;                        ; :scroll-top @scroll ;wonder why this doesnt work
;                        #_:style #_{:max-height @scroll}}
;        [:table>tbody.log
;         (for [msg (map (:messages @diag)
;                        (sort (keys (:messages @diag)))
;                        #_(if (:minimized @options)
;                                           [(count (:messages @diag))]
;                                           (sort (keys (:messages @diag)))))]
;          ^{:key (str (:id msg))}
;          [line msg])]]])})))

; (defn modal "Container for anything modal, taking care of common stuff. USE FOR COOKIE NOTICE LOL YES"
;  [component & [on-outside-click]]
;  (let []
;   (db/set [:modal] true)
;   [:div#modal-container
;    [:div#modal-bg {:on-click on-outside-click
;                    :style {:position :fixed
;                            :width "100%" :height "100%" :top 0 :left 0
;                            :background "rgb(30, 30, 30, 0.5)"}}]
;    [:div#modal {:class (when (db/get [:modal]) "modal-is-open")}
;     component]]))

; (defn hud-modal "Show more info about a specific HUD message"
;  [] ;doesnt really have to be modal but wanted to implement that, so...
;  (if-let [msg @(rf/subscribe   [:hud :modal])]
;   (let [to-close #(rf/dispatch [:hud :modal :remove])]
;    [modal [:div.hud-modal-main
;            {:class (str "hud-message " (name (:level msg)))}
;            [:h3  (:title   msg)]
;            [:p   (str (:message msg))]
;            [:p   (str (:time    msg))]
;            [ui/close to-close]]
;     to-close])
;   (db/set [:modal] false))) ;eww gross

; (defn hud "Render a HUD sorta like figwheel's but at reagent/re-frame level" []
;  (let [to-show @(rf/subscribe [:hud])
;        one-msg (fn [{:keys [level title message time actions id]}]
;                 (let [class (str "hud-message " (name level))]
;                  [:div.hud-message
;                   {:class class
;                    :style {:position :relative}
;                    :on-click #(rf/dispatch (or (:on-click actions)
;                                                [:hud :modal id])) }
;                   [:span title]
;                   [ui/close (fn [e]
;                              (.stopPropagation e) ;it's causing a click on hud-message as well...
;                              (rf/dispatch [:diag/unhandled :remove id]))]]))]
;   [:div.hud.hidden
;    {:class (when (seq to-show) "visible")}
;    [ui/flip-move
;     {:class "hud-messages"
;      :duration 200 :staggerDelayBy 20 :staggerDurationBy 30}
;     (for [msg to-show]
;      [one-msg msg])]]))


; (defn divider []) ;need nice generic solution


