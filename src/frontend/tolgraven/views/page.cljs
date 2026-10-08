(ns tolgraven.views.page
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :as m :refer-macros [defc defpage]]
    [tolgraven.react :as rf]
    [tolgraven.component :as component]
    [tolgraven.component.restore :as restore]
    [tolgraven.component.motion :as motion]
    [tolgraven.page-transition :as transition]
    [tolgraven.modules.main.module :as main-module]
    [tolgraven.loader :as l]
    [tolgraven.ui :as ui]
    [tolgraven.views-common :as common]))

(def spec main-module/spec)

(defn use-page-crossfade
  "Page-root crossfade lifecycle. Route commits never wait for this motion."
  [incoming outgoing current previous animate? transition-id & [outgoing-top]]
  (let [page-key (fn [route] (or (get-in route [:data :transition-key]) (:path route)))
        current-key (page-key current)
        previous-key (page-key previous)
        token (pr-str [transition-id previous-key current-key])
        [running set-running!] (rf/use-state nil)
        [finished set-finished!] (rf/use-state nil)
        [offset set-offset!] (rf/use-state nil)
        *incoming (rf/use-ref nil)
        *outgoing (rf/use-ref nil)
        force? (or (not animate?) (nil? outgoing) (= current-key previous-key))]
    (rf/use-layout-effect
      (fn []
        (if force?
          js/undefined
          (do
            ;; Route positioning runs first. Retain the outgoing viewport position
            ;; through that scroll change, as the native snapshot path does.
            (set-offset! [token (if (and (number? outgoing-top) (.-current *outgoing))
                                 (- outgoing-top (.-top (.getBoundingClientRect (.-current *outgoing))))
                                 0)])
            (motion/next-frame! #(set-running! token)))))
      #js [token force? outgoing-top])
    (rf/use-effect
      (fn []
        (if (and (not force?) (= token running) (.-current *outgoing))
          ;; Release the outgoing page when its CSS fade ends, not on a second
          ;; hard-coded clock. Both layers crossfade over the same interval.
          (do
            (doseq [element [(.-current *incoming) (.-current *outgoing)]]
              (when (and element (.-getAnimations element))
                (motion/prefer-high-frame-rate! (array-seq (.getAnimations element)))))
            (motion/finish-animation! (.-current *outgoing) {} #(set-finished! token)))
          js/undefined))
      #js [token running force?])
    [:<>
     (for [[key active? form] (cond-> [[current-key true incoming]]
                                (and (not force?) (not= token finished))
                                (conj [previous-key false outgoing]))]
       ^{:key (pr-str key)}
       [:r> (rf/context-provider component/page-root-context)
        #js {:value {:ref (if active? *incoming *outgoing)
                 :style (when (and (not active?) (= token (first offset)))
                          {:translate (str "0 " (second offset) "px")})
                 :aria-hidden (when-not active? true)
                 :inert (when-not active? true)
                 :class (if active?
                          (str "swap-in opacity " (when force? "swap-static ") (when (or force? (= token running) (= token finished)) "swapped-in"))
                          (str "swapped " (when (= token running) "opacity swapped-out")))}}
        form])]))

(defpage <page> "Render active page inbetween header, footer and general stuff."
  {:container false}
  []
  (let [commit @(rf/subscribe [:get :page/commit])
        _ (transition/use-ready! commit)
        ext-back? (restore/skip-enter?)
        debug @(rf/subscribe [:state [:debug]])
        click-evt @(rf/subscribe [:state [:global-clicked]])
        page @(rf/subscribe [:common/page])
        previous @(rf/subscribe [:common/page :last])
        layers (use-page-crossfade
                 (when page [(component/resolve-view page)])
                 (when previous [(component/resolve-view previous)])
                 @(rf/subscribe [:common/route]) @(rf/subscribe [:common/route :last])
                 (and (not ext-back?) (get-in commit [:completion :fallback?]))
                 (get-in commit [:completion :transition-id])
                 (get-in commit [:completion :outgoing-top]))]
  [:<>
   [l/<loaded-assets> (:assets spec)]

   [common/<header> @(rf/subscribe [:content [:header]])]
   [:a {:name "linktotop" :id "linktotop"}]

   [ui/<zoom-to-modal> :fullscreen]
   (m/<> {:module :link-preview})
   (m/<> {:module :user, :defer? true})
   [common/<settings>]
   (m/<> {:module :search, :defer? true})
   (if-let [error-page @(rf/subscribe [:state [:error-page]])] ; do it like this as to not affect url. though avoiding such redirects not likely actually useful for an SPA? otherwise good for archive.org check hehe
     [:main.main-content.perspective-top
      [error-page]]
     (if page
       [:r> (rf/context-provider restore/readiness-context) #js {:value true}
        [:main.main-content.perspective-top
        {:id    "main"
         :style (when-let [height (get-in commit [:completion :outgoing-height])]
                  {:min-height (str height "px")})
         :data-debug-hydrated (when @(rf/subscribe [:state [:debug :hydration-token]]) true)
         :data-restored (when ext-back? true)
         :data-stream-enter (when (restore/document-enter?) true)
         :class (str (when (and (not ext-back?) (not (restore/document-enter?))
                                     (= (:page @restore/*context) (restore/page-key)))
                            "animate ")
                     (when (:layers debug) "debug-layers ")
                     (when (:parallax debug) "debug-on"))}
        layers]]
       [ui/<loading-spinner> true :massive]))                 ; removed since jars now that have hero in original html

   [:div#error-portal]

   [common/<footer-full> @(rf/subscribe [:content [:footer]])]
   [common/<footer> @(rf/subscribe [:content [:footer]])]
   [ui/<hud> (rf/subscribe [:hud])]
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
