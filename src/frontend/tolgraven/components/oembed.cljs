(ns tolgraven.components.oembed
  (:require
    [tolgraven.component.registry]
    [tolgraven.component.data :as data]
    [tolgraven.components.oembed-schema :as schema]
    [tolgraven.react :as rf]
    [tolgraven.components.image :as img]
    [tolgraven.macros :as m]
    [tolgraven.components.ui :as ui]))

(defn dependency [url]
  {:source :url
   :url (str "/api/oembed?url=" (js/encodeURIComponent url))
   :ttl-ms 60000})

(m/defc ^:private <loading> [_ placeholder]
  (or placeholder [:p "Loading player…"]))

(m/defc ^:private <player> [result :- schema/result]
  ;; Provider markup runs in an opaque-origin document. Never combine
  ;; allow-scripts with allow-same-origin for this untrusted srcDoc boundary.
  [:iframe.oembed-inner
   {:title (or (:title result) "Embedded player")
    :sandbox "allow-scripts"
    :referrer-policy "no-referrer"
    :style {:width "100%"
            :border "none"
            :height (str (/ (max 80 (min 640 (or (:height result) 166))) 16) "rem")}
    :src-doc (str "<!doctype html><html><head><meta name='viewport' content='width=device-width'>"
                  "<style>body{margin:0}iframe{display:block;width:100%;border:0}</style>"
                  "</head><body>" (or (:html result) "") "</body></html>")}])

(m/defc <oembed-view>
  {:depends (fn [url _] [(dependency url)])
   :loading <loading>}
  [url :- :string placeholder]
  (let [[hovered? set-hovered!] (rf/use-state false)
        result (:value (data/snapshot (dependency url)))]
    [:div.oembed.parallax-sm
     {:class (when hovered? "hovered")
      :on-mouse-enter #(set-hovered! true)
      :on-mouse-leave #(set-hovered! false)}
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
