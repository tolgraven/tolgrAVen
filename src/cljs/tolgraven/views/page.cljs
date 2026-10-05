(ns tolgraven.views.page
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :as m :refer-macros [defc defpage]]
    [tolgraven.react :as rf]
    [tolgraven.component :as component]
    [tolgraven.component.restore :as restore]
    [tolgraven.page-transition :as transition]
    [tolgraven.main.module :as main-module]
    [tolgraven.loader :as l]
    [tolgraven.ui :as ui]
    [tolgraven.views-common :as common]))

(def spec main-module/spec)

(defc <swapper>
  "CSS crossfade fallback. Route commits never wait for this page-owned motion."
  [incoming outgoing current previous animate? transition-id]
  (let [page-key (fn [route] (or (get-in route [:data :transition-key]) (:path route)))
        current-key (page-key current)
        previous-key (page-key previous)
        token (pr-str [transition-id previous-key current-key])
        [running set-running!] (rf/use-state nil)
        [finished set-finished!] (rf/use-state nil)
        force? (or (not animate?) (nil? outgoing) (= current-key previous-key))]
    (rf/use-effect
      (fn []
        (if force?
          js/undefined
          (let [*frame (atom nil)
                *timer (atom nil)]
            (reset! *frame
              (js/requestAnimationFrame
                (fn [_]
                  (reset! *frame
                    (js/requestAnimationFrame
                      (fn [_]
                        (set-running! token)
                        (reset! *timer (js/setTimeout #(set-finished! token) 750))))))))
            (fn []
              (when @*frame (js/cancelAnimationFrame @*frame))
              (when @*timer (js/clearTimeout @*timer))))))
      #js [token force?])
    [:div.swapper
     (for [[key active? form] (cond-> [[current-key true incoming]]
                                (and (not force?) (not= token finished))
                                (conj [previous-key false outgoing]))]
       ^{:key (pr-str key)}
       [:div {:aria-hidden (when-not active? true)
              :inert (when-not active? true)
              :class (if active?
                       (str "swap-in opacity " (when (or force? (= token running) (= token finished)) "swapped-in"))
                       (str "swapped " (when (= token running) "opacity swapped-out")))}
        form])]))

(defpage <page> "Render active page inbetween header, footer and general stuff."
  []
  (let [commit @(rf/subscribe [:get :page/commit])
        _ (transition/use-ready! commit)
        ext-back? (restore/skip-enter?)
        debug @(rf/subscribe [:state [:debug]])
        click-evt @(rf/subscribe [:state [:global-clicked]])]
  [:<>
   [l/<loaded-assets> (:assets spec)]

   [ui/<safe> :header [common/<header> @(rf/subscribe [:content [:header]])]]
   [:a {:name "linktotop" :id "linktotop"}]

   [ui/<zoom-to-modal> :fullscreen]
   (m/view {:module :link-preview})
   [ui/<safe> :user (m/view {:module :user, :defer? true})]
   [ui/<safe> :settings [common/<settings>]]
   [ui/<safe> :search (m/view {:module :search, :defer? true})]
   (if-let [error-page @(rf/subscribe [:state [:error-page]])] ; do it like this as to not affect url. though avoiding such redirects not likely actually useful for an SPA? otherwise good for archive.org check hehe
     [:main.main-content.perspective-top
      [error-page]]
     (if-let [page @(rf/subscribe [:common/page])]
       [:main.main-content.perspective-top
        {:id    "main"
         :data-debug-hydrated (when @(rf/subscribe [:state [:debug :hydration-token]]) true)
         :data-restored (when ext-back? true)
         :data-stream-enter (when (restore/initial-enter?) true)
         :class (str (when (and (not ext-back?)
                                     (= (:page @restore/*context) (restore/page-key)))
                            "animate ")
                     (when (:layers debug) "debug-layers ")
                     (when (:parallax debug) "debug-on"))}
        [<swapper>
         [ui/<safe> :page [(component/resolve-view page)]
          (:path @(rf/subscribe [:common/route]))]
         (when-let [previous @(rf/subscribe [:common/page :last])]
           [ui/<safe> :page [(component/resolve-view previous)]
            (:path @(rf/subscribe [:common/route :last]))])
         @(rf/subscribe [:common/route]) @(rf/subscribe [:common/route :last])
         (and (not ext-back?) (get-in commit [:completion :fallback?]))
         (get-in commit [:completion :transition-id])]]
       [ui/<loading-spinner> true :massive]))                 ; removed since jars now that have hero in original html

   [:div#error-portal]

   [common/<footer-full> @(rf/subscribe [:content [:footer]])]
   [common/<footer> @(rf/subscribe [:content [:footer]])]
   [ui/<safe> :hud [ui/<hud> (rf/subscribe [:hud])]]
   [common/<to-top>]
   ; [[:div.ripple-on-click
   ;    {:class (when click-evt "ripple")
   ;     :style {:left (str "calc(" (if click-evt
   ;                                  (.-pageX click-evt)
   ;                                  0)
   ;                        "px - " 7 "em)")
   ;             :top (str "calc(" (if click-evt
   ;                                 (.-pageY click-evt)
   ;                                 0)
   ;                       "px - " 5 "em)")}}]common/scrollbar {}]

   [:a {:name "bottom" :id "bottom"}]]))
