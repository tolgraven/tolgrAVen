(ns website.components.page
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  #?(:cljs (:require
    [website.component.registry]
    [website.macros :as m :refer-macros [defc defpage]]
    [website.react :as r]
    [website.component :as component]
    [website.component.restore :as restore]
    [website.component.motion :as motion]
    [website.navigation.transition :as transition]
    [website.modules.main.module :as main-module]
    [website.loader :as l]
    [website.components.ui.loading :as ui-loading]
    [website.components.ui.notifications :as ui-notifications]
    [website.components.ui.overlays :as ui-overlays]
    [website.components.shell :as common])))

#?(:cljs
   (do

(def spec main-module/spec)

(defn use-page-crossfade
  "Page-root crossfade lifecycle. Route commits never wait for this motion."
  [incoming outgoing current previous animate? transition-id & [outgoing-top]]
  (let [page-key (fn [route] (or (get-in route [:data :transition-key]) (:path route)))
        current-key (page-key current)
        previous-key (page-key previous)
        token [transition-id previous-key current-key]
        [running set-running!] (r/use-state nil)
        [finished set-finished!] (r/use-state nil)
        [offset set-offset!] (r/use-state nil)
        *incoming (r/use-ref nil)
        *outgoing (r/use-ref nil)
        force? (or (not animate?) (nil? outgoing) (= current-key previous-key))
        surface-props (fn [active?]
                        {:ref (if active? *incoming *outgoing)
                         :style (when (and (not active?) (= token (first offset)))
                                  {:translate (str "0 " (second offset) "px")})
                         :aria-hidden (when-not active? true)
                         :inert (when-not active? true)
                         :class (if active?
                                  (str "swap-in opacity " (when force? "swap-static ") (when (or force? (= token running) (= token finished)) "swapped-in"))
                                  (str "swapped " (when (= token running) "opacity swapped-out")))})
        incoming-props (surface-props true)
        outgoing-props (surface-props false)
        ;; React compares context values by identity. Controller-only route
        ;; updates must not invalidate pending SSR through equal surface props.
        incoming-props' (r/use-memo (fn [] incoming-props) [incoming-props])
        outgoing-props' (r/use-memo (fn [] outgoing-props) [outgoing-props])]
    (r/use-layout-effect
      (fn []
        (if force?
          nil
          (do
            ;; Route positioning runs first. Retain the outgoing viewport position
            ;; through that scroll change, as the native snapshot path does.
            (set-offset! [token (if (and (number? outgoing-top) (.-current *outgoing))
                                 (- outgoing-top (.-top (.getBoundingClientRect (.-current *outgoing))))
                                 0)])
            (motion/next-frame! #(set-running! token)))))
      [token force? outgoing-top])
    (r/use-effect
      (fn []
        (if (and (not force?) (= token running) (.-current *outgoing))
          ;; Release the outgoing page when its CSS fade ends, not on a second
          ;; hard-coded clock. Both layers crossfade over the same interval.
          (do
            (doseq [element [(.-current *incoming) (.-current *outgoing)]]
              (when (and element (.-getAnimations element))
                (motion/prefer-high-frame-rate! (array-seq (.getAnimations element)))))
            (motion/finish-animation! (.-current *outgoing) {} #(set-finished! token)))
          nil))
      [token running force?])
    [:<>
     (for [[key active? form] (cond-> [[current-key true incoming]]
                                (and (not force?) (not= token finished))
                                (conj [previous-key false outgoing]))]
       ^{:key (pr-str key)}
       [:r> (r/context-provider component/page-root-context)
        #js {:value (if active? incoming-props' outgoing-props')}
        form])]))

(defpage <page> "Render active page inbetween header, footer and general stuff."
  {:container false}
  []
  (let [commit @(r/subscribe [:get :page/commit])
        _ (transition/use-ready! commit)
        ext-back? (restore/skip-enter?)
        document-enter? (restore/document-enter?)
        debug @(r/subscribe [:state [:debug]])
        click-evt @(r/subscribe [:state [:global-clicked]])
        route @(r/subscribe [:common/route])
        chrome? (not= false (get-in route [:data :shell :chrome?]))
        page @(r/subscribe [:common/page])
        previous @(r/subscribe [:common/page :last])
        layers (use-page-crossfade
                 (when page [(component/resolve-view page)])
                 (when previous [(component/resolve-view previous)])
                 route @(r/subscribe [:common/route :last])
                 (and (not ext-back?) (get-in commit [:completion :fallback?]))
                 (get-in commit [:completion :transition-id])
                 (get-in commit [:completion :outgoing-top]))]
  [:<>
   [l/<loaded-assets> (:assets spec)]

   (when chrome? [common/<header> @(r/subscribe [:content/view [:header]])])
   [:a {:name "linktotop" :id "linktotop"}]

   [ui-overlays/<zoom-to-modal> :fullscreen]
   (m/<> {:module :link-preview :defer? true})
   (when-enabled :user nil
    (let [open? @(r/subscribe [:user/ui-open?])]
     ;; A query-string account deep link is intent too. Closed panels acquire
     ;; nothing; an activated module retains its existing presence lifecycle.
     (m/<> {:module :user
            :defer? (not open?)
            :load-on (if open? :immediate :event)})))
   [:div.menu-panels
    #?(:module/settings
       (let [open? @(r/subscribe [:state [:settings :panel-open]])]
         (m/<> {:module :settings
                :defer? (not open?)
                :load-on (if open? :immediate :event)})))
    (m/<> {:module :search, :defer? true})]
   (m/<> {:module :search, :view :results, :defer? true})
   (if-let [error-page @(r/subscribe [:state [:error-page]])] ; do it like this as to not affect url. though avoiding such redirects not likely actually useful for an SPA? otherwise good for archive.org check hehe
     [:main.main-content.perspective-top
      [error-page]]
     (if page
       [:r> (r/context-provider restore/readiness-context) #js {:value true}
        [:main.main-content.perspective-top
        {:id    "main"
         :style (when-let [height (get-in commit [:completion :outgoing-height])]
                  {:min-height (str height "px")})
         :data-debug-hydrated (when @(r/subscribe [:state [:debug :hydration-token]]) true)
         :data-restored (when (and ext-back? (not document-enter?)) true)
         :class (str (when-not chrome? "main-content--bare ")
                     (when (or document-enter?
                                 (and (not ext-back?)
                                      (= (:page @restore/*context) (restore/page-key))))
                            "animate ")
                     (when (:layers debug) "debug-layers ")
                     (when (:parallax debug) "debug-on"))}
        layers]]
       [ui-loading/<loading-spinner> true :massive]))                 ; removed since jars now that have hero in original html

   [:div#error-portal]

   (when chrome?
     [:<>
      [common/<footer-full> @(r/subscribe [:content/view [:footer]])]
      [common/<footer> @(r/subscribe [:content/view [:footer]])]])
   (when-enabled :contact nil
     (let [open? @(r/subscribe [:state [:contact-form :show?]])]
       ;; The lazy host owns presence; its panel exists only while open/exiting.
       (m/<> {:module :contact
              :view :popup
              :defer? (not open?)
              :load-on (if open? :immediate :event)
              :<loading> (if open? [:span {:role "status"} "Opening contact form…"] [:<>])})))
   [ui-notifications/<hud> (r/subscribe [:hud])]
   (when-enabled :user nil
     (let [editor @(r/subscribe [:get :state :content-editor])]
       (when (or (:enabled? editor) (seq (:fields editor)))
         (m/<> {:module :content-editor :view :toolbar :load-on :immediate}))))
   (when chrome? [common/<to-top>])
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

))
