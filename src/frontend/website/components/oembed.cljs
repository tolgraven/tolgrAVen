(ns website.components.oembed
  (:require
    [website.component]
    [website.component.registry]
    [website.component.data :as data]
    [website.components.oembed.contract :as schema]
    [website.react :as r]

    [website.components.image :as img]
    [website.macros :as m]
    [website.components.ui.boundary :as ui-boundary]))

(defn dependency [url]
  {:source :url
   :url (str "/api/oembed?url=" (js/encodeURIComponent url))
   :ttl-ms 60000})

(m/defc ^:private <loading> [_ placeholder]
  (or placeholder [:p "Loading player…"]))

(m/defc ^:private <player> [result :- schema/result]
  ;; Arbitrary markup stays opaque. Recognized cross-origin players need their
  ;; own origin for storage/API requests, and never receive the provider HTML.
  (let [source (schema/player-url (:player-src result))
        attrs {:title (:title result)
               :referrer-policy "no-referrer"
               :style {:width "100%"
                       :border "none"
                       :height (str (max 5 (min 40 (/ (:height result) 16))) "rem")}}]
    [:iframe.oembed-inner
     (if source
       (assoc attrs :sandbox "allow-scripts allow-same-origin"
                    :allow "autoplay; encrypted-media; fullscreen"
                    :src source)
       (assoc attrs :sandbox "allow-scripts"
                    :src-doc (str "<!doctype html><html><head><meta name='viewport' content='width=device-width'>"
                                  "<style>body{margin:0}iframe{display:block;width:100%;border:0}</style>"
                                  "</head><body>" (:html result) "</body></html>")))]))

(m/defc <oembed-view>
  {:depends (fn [url _] [(dependency url)])
   :loading <loading>}
  [url :- :string placeholder]
  :let [*hovered? (r/atom false)]
  (let [result (:value (data/snapshot (dependency url)))]
    [:div.oembed.parallax-sm
     {:class (when @*hovered? "hovered")
      :on-mouse-enter #(reset! *hovered? true)
      :on-mouse-leave #(reset! *hovered? false)}
     [<player> result]]))

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
   [:h2.track-title song]
   [:p.track-artist artist]])

(m/defc <soundcloud-player>
  {:features [[:seen "slide-in"]]}
  [artist song]
  (let [base-url "https://soundcloud.com/"
        url      (str base-url artist "/" song)]
    [:div (if @(r/subscribe [:booted? :soundcloud])
       [ui-boundary/<safe> :player
        [<oembed-view> url [<soundcloud-loading> artist song]]]
       [<soundcloud-loading> artist song])]))

(m/defc <soundcloud> "Soundcloud feed, plus selected tunes. Bonus if can do anything fun with it"
  []
  (let [{:keys [artist tunes]} @(r/subscribe [:content [:soundcloud]])]
    [:section.soundcloud.fullwide.covering-3
     [:div.soundcloud-players.parallax-wrapper
      (m/for [tune tunes]
             [<soundcloud-player> artist tune])]]))
