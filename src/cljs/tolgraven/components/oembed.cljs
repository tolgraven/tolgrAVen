(ns tolgraven.components.oembed
  (:require
    [clojure.string :as string]
    [reagent.core :as r]
    [re-frame.core :as rf]
    [tolgraven.image :as img]
    [tolgraven.macros :as m]
    [tolgraven.ui :as ui]))

(defn oembed-view [url <loading>]
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

(defn soundcloud-loading "A dummy to show before initing react-player"
  [artist song]
  [:div.soundcloud-player-loading
   [img/picture
    {:src   "img/soundcloud-logo.png"
     :alt   "SoundCloud"
     :class "center-content"}]
   [:h3 song]
   [:h4 artist]])

(defn soundcloud-player
  [artist song]
  (let [base-url "https://soundcloud.com/"
        url      (str base-url artist "/" song)]
    [ui/seen-anon "slide-in"
     (if @(rf/subscribe [:booted? :soundcloud])
       [ui/safe :player
        [oembed-view url [soundcloud-loading artist song]]]
       [soundcloud-loading artist song])]))

(defn soundcloud "Soundcloud feed, plus selected tunes. Bonus if can do anything fun with it"
  []
  (let [{:keys [artist tunes]} @(rf/subscribe [:content [:soundcloud]])]
    [:section.soundcloud.fullwide.covering-3
     [:div.soundcloud-players.parallax-wrapper
      (m/for [tune tunes]
             [soundcloud-player artist tune])]]))
