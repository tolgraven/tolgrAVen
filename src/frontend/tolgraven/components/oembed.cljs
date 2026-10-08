(ns tolgraven.components.oembed
  (:require
    [tolgraven.component.registry]
    [clojure.string :as string]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [tolgraven.components.image :as img]
    [tolgraven.macros :as m]
    [tolgraven.components.ui :as ui]))

(m/defc <oembed-view> [url <loading>]
  (let [state    (r/atom {:loading? true})
        hovered? (r/atom false)
        <comp>   (fn []
                   [:div.oembed-inner
                    {:style {:height (:height @state)}
                     :dangerouslySetInnerHTML
                     {:__html (get-in @state [:data :html])}}])]
    (rf/dispatch [:http/get {:uri    "/api/oembed"
                             :params {:url url}}
                  #(r/rswap! state merge {:loading? false
                                          :data %})
                  #(r/rswap! state merge {:loading? false
                                          :error %})])
    (fn [_ _]
      (let [{:keys [loading? data error]} @state]
        [:div.oembed.parallax-sm
         {:class          (when @hovered? "hovered")
          :on-mouse-enter #(do (reset! hovered? true)
                               (js/console.log "hovered embed"))
          :on-mouse-leave #(do (reset! hovered? false)
                               (js/console.log "unhovered embed"))}
         (cond
           (and loading? <loading>) <loading>
           error "Failed to load embed"
           :else [<comp>])]))))

;; Unfinished React-player alternative; oEmbed remains the active player.
(m/defc <remote-player>
  [url]
  [:div "Soundcloud player hey"]
  #_[:> SoundCloud
   {:url url
    :width "100%"
    :height "100%"}])

(m/defc <soundcloud-loading> "Placeholder shown while the SoundCloud embed loads"
  [artist song]
  [:div.soundcloud-player-loading
   [img/<picture>
    {:src   "img/soundcloud-logo.png"
     :alt   "SoundCloud"
     :class "center-content"}]
   [:h3 song]
   [:h4 artist]])

(m/defc <soundcloud-player>
  {:features [[:seen "slide-in"]]}
  [artist song]
  (let [base-url "https://soundcloud.com/"
        url      (str base-url artist "/" song)]
    [:div (if @(rf/subscribe [:booted? :soundcloud])
       [ui/<safe> :player
        [<oembed-view> url [<soundcloud-loading> artist song]]]
       [<soundcloud-loading> artist song])]))

(m/defc <soundcloud> "Soundcloud feed, plus selected tunes. Bonus if can do anything fun with it"
  []
  (let [{:keys [artist tunes]} @(rf/subscribe [:content [:soundcloud]])]
    [:section.soundcloud.fullwide.covering-3
     [:div.soundcloud-players.parallax-wrapper
      (m/for [tune tunes]
             [<soundcloud-player> artist tune])]]))
