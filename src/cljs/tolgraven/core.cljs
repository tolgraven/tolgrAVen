(ns tolgraven.core
  (:require
    [clojure.string :as string]
    [goog.events]
    [re-frame.core :as rf]
    [react :as react]
    [reagent.core :as r]
    [reagent.dom.client :as rdomc]
    [reitit.core :as reitit]
    [reitit.dev.pretty :as rpretty]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.history :as rfh]
    [tolgraven.ajax :as ajax]
    [tolgraven.events]
    [tolgraven.loader :as l]
    [tolgraven.macros :as m]
    [tolgraven.subs]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views :as view]
    [tolgraven.views-common :as common]))


; (defn swapper "Swap between outgoing and incoming page view"
;   [class comp-1 comp-2]
;   (let [swap (rf/subscribe [:state [:swap]])
;         curr-page (-> (rf/subscribe [:common/route ]) deref :data :name)
;         prev-page (-> (rf/subscribe [:common/route :last]) deref :data :name)
;         curr-1 (r/atom curr-page)
;         curr-2 (r/atom nil)
;         curr-visible (r/atom curr-page) ; well would have to be a sub tho
;         curr-hidden (r/atom nil)]
;     [:div.swapper
;      [:div.swap-one
;       {:class (str class " "
;                    (when (and (= @curr-visible @curr-1)
;                               (or (not comp-2)
;                                   (not @swap)
;                                   (= (:running @swap) @curr-hidden)
;                                   (= (:finished @swap) @curr-hidden)))
;                      "swapped-in")
;                    (when (= (:running @swap) @curr-1)
;                        class) " "
;                      (when (= (:running @swap) @curr-1)
;                        "swapped-out") " "
;                      (when (= (:finished @swap) @curr-1)
;                        "removed"))}
;       comp-1] ;will have to be behind for z then revealed by curr page moving out the way.
;      (when comp-2
;        [:div.swapped
;         {:class (str (when (and (= @curr-visible @curr-2)
;                                 (or (= (:running @swap) prev-page)
;                                     (= (:finished @swap) prev-page)))
;                        "swapped-in")
;                      (when (= (:running @swap) @curr-2)
;                        class) " "
;                      (when (= (:running @swap) @curr-2)
;                        "swapped-out") " "
;                      (when (or (= (:finished @swap) @curr-2)
;                                (not @swap))
;                        "removed") " ")
;          :ref #(when (and %
;                           (not= @curr-hidden (:running @swap))
;                           (not= @curr-hidden (:finished @swap)))
;                (rf/dispatch [:swap/trigger prev-page]))} ; trigger anim out and deferred hiding. triggers three(!) times each time but later no effect so.
;         comp-2])]))

(defn swapper "Swap between outgoing and incoming page view"
  [class comp-in comp-out]
  (let [swap (rf/subscribe [:state [:swap]])
        curr-page (rf/subscribe [:common/route ])
        prev-page (rf/subscribe [:common/route :last])
        force? (empty? (seq class))
        ref-fn #(when (and %
                           (not= (get-in @prev-page [:data :name])
                                 (:running @swap))
                           (not= (get-in @prev-page [:data :name]) (:finished @swap)))
                 (rf/dispatch [:swap/trigger prev-page]))] ; no transition
    (fn [class comp-in comp-out]
      (let [prev-page (get-in @prev-page [:data :name])]
        [:div.swapper
         [:div.swap-in
          {:class (str class " "
                       (when (or force?
                                 (not comp-out)
                                 (not @swap)
                                 (= (:running @swap) prev-page)
                                 (= (:finished @swap) prev-page))
                         "swapped-in"))}
          comp-in] ;will have to be behind for z then revealed by curr page moving out the way.
         (when comp-out
           [:div.swapped
            {:class (str (when (= (:running @swap) prev-page)
                           class) " "
                         (when (= (:running @swap) prev-page)
                           "swapped-out") " "
                         (when (or (= (:finished @swap) prev-page)
                                   (not @swap)
                                   force?)
                           "removed") " ")
             :ref #(do (when (and %
                                  (not= prev-page (:running @swap))
                                  (not= prev-page (:finished @swap)))
                         (rf/dispatch [:swap/trigger prev-page]))
                       (rf/dispatch [:history/set-referrer [nil 0]]))} ; trigger anim out and deferred hiding. triggers three(!) times each time but later no effect so.
            (when-not (:finished @swap)
              comp-out)])]))))
#_(defn swapper "Swap between outgoing and incoming page view"
  [class comp-in comp-out]
  #_[:div "aj"]
  (let [swap (rf/subscribe [:state [:swap]])
        curr-page (-> (rf/subscribe [:common/route ]) deref :data :name)
        prev-page (-> (rf/subscribe [:common/route :last]) deref :data :name)
        force? (empty? (seq class))
        ref-fn #(when (and %
                          (not= prev-page (:running @swap))
                          (not= prev-page (:finished @swap)))
                 (rf/dispatch [:swap/trigger prev-page]))] ; no transition
    (fn [class comp-in comp-out]
      [:div.swapper
     [:div.swap-in
      {:class (str class " "
                   (when (or force?
                             (not comp-out)
                             (not @swap)
                             (= (:running @swap) prev-page)
                             (= (:finished @swap) prev-page))
                     "swapped-in"))}
      comp-in] ;will have to be behind for z then revealed by curr page moving out the way.
     (when comp-out
       [:div.swapped
        {:class (str (when (= (:running @swap) prev-page)
                       class) " "
                     (when (= (:running @swap) prev-page)
                       "swapped-out") " "
                     (when (or (= (:finished @swap) prev-page)
                               (not @swap)
                               force?)
                       "removed") " ")
         :ref #(when (and %
                          (not= prev-page (:running @swap))
                          (not= prev-page (:finished @swap)))
               (rf/dispatch [:swap/trigger prev-page]))} ; trigger anim out and deferred hiding. triggers three(!) times each time but later no effect so.
        (when-not (:finished @swap)
          comp-out)])])))
;; ^^ this works but obviously should be going back and forth between two "equal" comps
;; that are therefore not being reloaded on leaving...

(defn <assets> "Load general assets"
  []
  [:<>
   (m/for [css @(rf/subscribe [:loader/css])]
          [:link {:rel  "stylesheet"
                  :type "text/css"
                  :href css}])
   (m/for [js @(rf/subscribe [:loader/js])]
          [:script {:type "text/javascript"
                    :src  js}])])

(defn page "Render active page inbetween header, footer and general stuff." 
  []
  (let [ext-back? @(rf/subscribe [:history/back-nav-from-external?])
        swap-class (if ext-back? "" "opacity")
        click-evt @(rf/subscribe [:state [:global-clicked]])]
  [:<>
   [<assets>]

   [ui/safe :header [common/header @(rf/subscribe [:content [:header]])]] ;TODO smooth transition to personal
   [:a {:name "linktotop" :id "linktotop"}]
   
   [ui/zoom-to-modal :fullscreen]
   [ui/safe :user [l/<> {:module :user, :view :view}]]
   [ui/safe :settings [common/settings]]
   [ui/safe :search [l/<> {:module :search, :view :view}]]
   (if-let [error-page @(rf/subscribe [:state [:error-page]])] ; do it like this as to not affect url. though avoiding such redirects not likely actually useful for an SPA? otherwise good for archive.org check hehe
     [:main.main-content.perspective-top
      [error-page]]
     (if-let [page @(rf/subscribe [:common/page])]
       [:main.main-content.perspective-top
        {:id    "main"
         :class (when-not ext-back? "animate")}
        [swapper swap-class
         [ui/safe :page [page]]
         (when-let [page-prev @(rf/subscribe [:common/page :last])]
           [ui/safe :page-prev [page-prev]])]]
       [ui/loading-spinner true :massive]))                 ; removed since jars now that have hero in original html

   [:div#error-portal]

   [common/footer-full @(rf/subscribe [:content [:footer]])]
   [common/footer @(rf/subscribe [:content [:footer]])]
   [ui/safe :hud [ui/hud (rf/subscribe [:hud])]]
   [common/to-top]
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

(defn log-page []
  [ui/with-heading [:common :banner-heading]
   [ui/log (rf/subscribe [:option [:log]])
    (rf/subscribe [:get :diagnostics])]
   {:title "Log" :tint "blue"}])

(defn not-found-page []
  [ui/with-heading [:common :banner-heading]
   [:div.center-content
    [:br] [:p "Four, oh four. Nothing to see here, move along."]]
   {:title "Not found" :tint "red"}])

(def router ; XXX weird thing doesnt automatically scroll to top when change page...
  (reitit/router
    ["/"
     {:controllers [{:parameters {:query [:userBox :settingsBox]} ; ok so this how done. but surely will get unwieldy af?
                     :start (fn [{:keys [query]}]    ; and how get to update url with changes...
                              (case (:userBox query) ; was thinking "have :user/open-ui passing true/false and no case but would be spammy"
                               "true" (rf/dispatch [:user/open-ui])
                               ("false" nil) (rf/dispatch [:user/close-ui]))
                              (case (:settingsBox query)
                                "true" (do (rf/dispatch [:state [:settings :panel-open] true])
                                           (rf/dispatch [:scroll/to-top-and-arm-restore]))
                                ("false" nil) (rf/dispatch [:state [:settings :panel-open] false]))) ; well this being on start it wouldn't be open anyways
                     :stop (fn [{:keys [query]}]    ; why is this being run without leaving page?
                             )}]}
     [""
      {:name        :home
       :view        #'view/ui-auto
       :controllers [{:start (fn [{:keys [_]}]
                               (rf/dispatch [:state [:is-personal] false])
                               (rf/dispatch [:page/init-home]))}]}]
     ["about"
      {:name        :about
       :view        #'view/ui-auto
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "about" 700]))}]}]
     ["services"
      {:name        :services
       :view        #'view/ui-auto
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "main" 700]) ; hack because stickied so "already there"...
                               (rf/dispatch [:scroll/to "section-services" 1300]))}]}]
     ["hire"
      {:name        :hire
       :view        #'view/ui-auto
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "bottom" 700]))}]}]
     ["cv"
      {:name        :cv
       :module      :cv
       :view        :page
       :controllers [{:stop (fn [_]
                              (rf/dispatch [:state [:fullscreen :cv] false]))}]}]
     ["docs"
      ["" {:name   :docs
           :module :docs
           :page   :page}]
      ["/codox/:doc"
       {:name   :docs-codox-page
        :module :docs
        :page   :page
        :controllers
        [{:parameters {:path [:doc]}
          :start      (fn [{:keys [path]}]
                        (rf/dispatch [:docs/get (:doc path)])
                        (rf/dispatch [:docs/set-page (:doc path)]))}]}]]
     
     ["blog"
      ["" {:name        :blog
           :module      :blog
           :page        :page
           :controllers [{:start (fn [_]
                                   (rf/dispatch [:blog/nav-page 1]) ; down here so back-btn works from page/2 to blog aka page/1.
                                   (rf/dispatch [:->css-var! "line-width" "1px"]) ; TODO fix so does this without hardcoding either. Might also set line-color to something less pronounced.
                                   (rf/dispatch [:->css-var! "line-width-vert" "1px"]))
                          :stop  (fn []
                                   (rf/dispatch [:->css-var! "line-width" "2px"])
                                   (rf/dispatch [:->css-var! "line-width-vert" "2px"]))}]}] ; needed here so going back from blog/page/2 to blog returns one to page 1...
      ["/page/:nr"
       {:name   :blog-page
        :module :blog
        :page   :page
        :controllers
        [{:parameters {:path [:nr]}
          :start      (fn [{:keys [path]}]
                        (rf/dispatch [:blog/nav-action (:nr path)]))
          :stop       (fn [{:keys [path]}])}]}]
      ["/post/:permalink"
       {:name   :blog-post
        :module :blog
        :page   :post
        :controllers
        [{:parameters {:path [:permalink]}
          :start      (fn [{:keys [path] :as data}]
                        (let [id (-> path :permalink (string/split "-") last js/parseInt)]
                          (rf/dispatch [:blog/state [:current-post-id] id])
                          #_(rf/dispatch [:common/set-title "Blog post title: not implemented"])))
          :stop       (fn [{:keys [path] :as data}]
                        (rf/dispatch [:blog/state [:current-post-id] nil])
                        (rf/dispatch [:common/set-title nil]))}]}]
      ["/archive" {:name   :blog-archive
                   :module :blog
                   :page   :archive}]
      ["/tag/:tag" {:name   :blog-tag
                    :module :blog
                    :page   :tag
                    :controllers
                    [{:parameters {:path [:tag]}
                      :start      (fn [{:keys [path]}]
                                    (rf/dispatch [:blog/state [:viewing-tag] (:tag path)]))
                      :stop       (fn [{:keys [path]}]
                                    (rf/dispatch [:blog/state [:viewing-tag] nil]))}]}]
      ["new-post" {:name        :new-post
                   :module      :blog
                   :page        :new-post
                   :controllers [{:start (fn [_] (rf/dispatch [:blog/init-posting]))}
                                 {:stop (fn [_] (rf/dispatch [:blog/cancel-edit]))}]}]]


     ["log" {:name :log
              :view #'log-page}]
     ["test" 
      ["" {:name :test
           :module :test
           :page :page}]
      ["/:tab"
       {:name :test-tab
        :module :test
        :page :page
       :controllers [{:parameters {:path [:tab]} ; seems like a nice middle ground of using routing and urls but not fully integrating (needing these views available/known here for example). So, keep doing sub tabs like this?
                      :start (fn [{:keys [path]}]
                               (rf/dispatch [:state [:experiments] (keyword (:tab path))])
                               (rf/dispatch [:exception [:experiments] nil]))}]}]]
     ["client-oauth" ; for oauth flows. can capture results and send straight to firebase instead of going past our server
      ["" {:name :client-oauth
           :view #'not-found-page #_#'successful-oauth-page}]
      #_["/:service" ; nope, considering non-universal naming unless can coerce keys to universal api/secret/etc...
       {:name :client-api-service
        :view #'test-page
       :controllers [{:parameters {:path [:service]}
                      :start (fn [{:keys [path]}]
                               )}]}]
      ["/twitter"
       {:name :client-oauth-twitter
       :controllers
       [{:parameters {:query [:oauth_token
                              :oauth_token_secret
                              :oauth_callback_confirmed]}
         :start (fn [{:keys [query]}]
                  (if (:oauth_callback_confirmed query)
                      (rf/dispatch [:oauth/store-token-twitter
                                    (:oauth_token query) (:oauth_token_secret query)])
                      (rf/dispatch [:diag/new :error "Twitter auth" "Error authencicating"])))}]}]]
     ["not-found" {:name :not-found
                   :view #'not-found-page}]
     {:exception rpretty/exception
      :conflicts nil
      #_(fn [conflicts]
        (println (exception/format-exception :path-conflicts nil conflicts)))
      ; :data {:controllers [{:parameters {:query [:userBox]}
      ;                       ; :start #(rf/dispatch [:common/navigate! :home]) ;would work! except hijacks
      ;                       :start (fn [{:keys [query]}]
      ;                                (util/log :info "query-params" query)
      ;                                (rf/dispatch [:common/navigate! :home])) ;would work! except hijacks
      ;                       ; also interesting doesnt seem to trigger when wrapped as fn?
      ;                       ; A shouldnt be like that considering controllers contain fns no? all rest do
      ;                       ; B how does it even happen wouldnt it trigger right away?
      ;                       ; well i mean thats what it does ;) but
     ; ; {:data {:controllers [{:start (rf/dispatch [:set [:common/route] ]) ;or just manually place :home so common/route returns it by default...
      ;                       :stop  (fn [_])}]}
      } ]))

(defn on-nav [match history]
  (if match ; cant do fallback route in router apparently, but we get nil matches so can use that
    ; TODO if match is same as current, skip navigate but maybe dispatch a scrolltotop
    ; hell if same as current maybe we can get passed any #anchor and manually scroll there?
    ; TODO if match lacks view could use controllers for just actions taken on [whatever current] page
    ; which could do anchor link simulation
    ; (let [*loadable (-> match :data :load lazy/loadable) ; should really be a full spec with (default) fallbacks etc
    (let [module (-> match :data :module) ; should really be a full spec with (default) fallbacks etc
          page   (-> match :data :page)
          name  (-> match :data :name)
          ; {:keys [start stop]} (-> match :data)
          ; controllers (-> match :data :controllers)
          ; start (util/wrap-fn (:start controllers))
          ->match   (fn [<comp>]
                      (update-in match [:data :view] #(if % % <comp>)))
          load-spec {:module  module
                     :view    page
                     :pre-fn  #(rf/dispatch-sync [:loading/on :page name])
                     :post-fn (fn [spec & _]
                                (rf/dispatch-sync [:common/navigate (->match (some-> spec :view page))])
                                (rf/dispatch [:loading/off :page name]))}]
      (when-let [<comp> (or (some-> match :data :view)
                            (some-> (l/load! load-spec) :view page))]
        (rf/dispatch-sync [:common/navigate (->match <comp>)]))) ; -sync avoids not having route when components mount
    (do
     (rf/dispatch-sync [:state [:error-page] not-found-page])
     (rf/dispatch [:diag/new :error "404" "Not found"]))))


(defn ignore-anchor-click?
  [router e el ^js uri]
  ; (js/console.log (.-fragment_ uri))
  (rf/dispatch-sync [:state [:fragment] (.-fragment_ uri)])
  (and #_:identical-uri ; cause pollutes history with duplicates
       #_:fragments-at-other-base-paths
       (rfh/ignore-anchor-click? router e el uri)))

(def router-settings
  {:use-fragment false
   :ignore-anchor-click? ignore-anchor-click?})

(defn start-router! []
  (rfe/start! router on-nav router-settings))


;; -------------------------
;; Initialize app

(defonce root (atom nil))
(defonce <page>
  (if false #_:biggus-debuggus
    (r/create-element react/StrictMode
                      nil                ;; <-- props
                      (r/as-element [page]))
    [#'page]))

(defn render []
  (let [app (.getElementById js/document "app")]
    (when @root
      (rdomc/unmount @root))
    (reset! root (rdomc/create-root app))
  ; (rdomc/render @root <page>))
  (rdomc/render @root [#'page])))

(defn mount-components "Called each update when developing" []
  ; save pos
  (rf/dispatch-sync [:scroll/save-position-dev])
  (rf/clear-subscription-cache!)
  (start-router!) ; restart router on reload?
  (rf/dispatch [:reloaded])
  (util/log "Mounting root component")
  (render)
  (rf/dispatch [:scroll/restore-position-dev 150]))

(defn init "Called only on page load" []
  (rf/dispatch-sync [:init/app-db])
  (rf/dispatch-sync [:fb/init]) ;sync because number of early fetches depend on this... move back in here though because useless, all public anyways + not like it's secret after sent off ;P was just to test concept
  (rf/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
  (ajax/load-interceptors!)
  (mount-components)
  (js/setTimeout #(rf/dispatch [:init/init]) ; listeners and stuff that might depend on being mounted
                 16))

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?

