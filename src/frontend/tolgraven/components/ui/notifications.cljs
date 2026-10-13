(ns tolgraven.components.ui.notifications
  (:require [tolgraven.component.registry]
            [tolgraven.react :as r]
            [tolgraven.macros :as m :include-macros true]
            [tolgraven.util :as util :refer [at]]
            [tolgraven.components.ui.controls :as controls]
            [tolgraven.components.ui.diagnostics :as diagnostics]
            [tolgraven.components.ui.overlays :as overlays]))


(m/defc <hud-modal> "Show more info about a specific HUD message"
 [] ;doesnt really have to be modal but wanted to implement that, so...
 (when-let [msg @(r/subscribe [:hud :modal])]
  (let [to-close #(r/dispatch [:hud :modal :remove])]
   [overlays/<modal> [:div.hud-modal-main
           {:class (str "hud-message " (name (:level msg)))}
           [:h3  (:title   msg)]
           [:p   (str (:message msg))]
           [:p   (str (:time    msg))]
           [controls/<close> to-close]]
    to-close])))

(m/defc <hud-message>
  {:features [[:appear "zoom-x slow"]]}
  [{:keys [level title message time actions buttons id]}]
  ;; Keep the existing animation box: message margins and its 0.9 opacity
  ;; remain independent of the entry animation. No separate appear component.
  [:div
   [:div.hud-message
    {:role (if (= :error level) "alert" "status")
     :class (name level)
     :style {:position :relative}
     :on-click #(doseq [action (or actions
                                  [[:diag/unhandled :remove id]
                                   [:common/navigate! :log]])]
                  (r/dispatch action))}
    [:div.hud-message-top
     [:h2.hud-message-title title]
     [controls/<close> (fn [event]
                (.stopPropagation event)
                (r/dispatch [:diag/unhandled :remove id]))]]
    (when message [:pre (diagnostics/format-log-message message)])
    (when buttons
      [:div.hud-message-buttons
       (for [{button-id :id :keys [text action]} buttons]
         ^{:key (str "hud-message-" id "-button-" button-id)}
         [:button.hud-message-button
          {:on-click (fn [event]
                       (.stopPropagation event)
                       (r/dispatch action))}
          text])])]])

(m/defc <hud> "Render a HUD sorta like figwheel's but at reagent/re-frame level"
  {:features [:error-boundary]}
  [to-show]
  [:div.hud.hidden
   {:class (when (seq @to-show) "visible")}
   (for [msg @to-show]
     ^{:key (str "hud-id-" (:id msg))}
     [<hud-message> msg])])
