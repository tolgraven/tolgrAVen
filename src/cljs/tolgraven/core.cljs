(ns tolgraven.core
  (:require
    [reagent.dom :as rdom]
    [reagent.core :as r]
    [reagent.dom.client :as rdomc]
    [re-frame.core :as rf]
    ; [kee-frame.core :as k]
    [tolgraven.ajax :as ajax]
    [tolgraven.events]
    [tolgraven.db :as db]
    [tolgraven.subs]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views :as view]
    [tolgraven.views-common :as common]
    [tolgraven.blog.views :as blog]
    [tolgraven.user.views :as user]
    [tolgraven.search.views :as search]
    [tolgraven.cv.views :as cv]
    [tolgraven.docs.views :as docs]
    [tolgraven.experiments :as experiment]
    [breaking-point.core :as bp]
    [reitit.core :as reitit]
    [reitit.frontend.history :as rfh]
    [reitit.frontend.easy :as rfe]
    [reitit.dev.pretty :as rpretty]
    [reitit.exception :as exception]
    [goog.events]
    [clojure.string :as string]
    [clojure.pprint :refer [pprint]])
  (:import [goog.History EventType]))


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


(defn page "Render active page inbetween header, footer and general stuff." 
  []
  (let [ext-back? @(rf/subscribe [:history/back-nav-from-external?])
        swap-class (if ext-back? "" "opacity")
        click-evt @(rf/subscribe [:state [:global-clicked]])]
  [:<>
   [ui/safe :header [common/header @(rf/subscribe [:content [:header]])]] ;TODO smooth transition to personal
   [:a {:name "linktotop" :id "linktotop"}]
   
   [ui/zoom-to-modal :fullscreen]
   [ui/safe :user [user/user-section @(rf/subscribe [:user/active-section])]]
   [ui/safe :settings [common/settings]]
   [ui/safe :search [search/ui]]
   (if-let [error-page @(rf/subscribe [:state [:error-page]])] ; do it like this as to not affect url. though avoiding such redirects not likely actually useful for an SPA? otherwise good for archive.org check hehe
     [:main.main-content.perspective-top
      [error-page]]
     (if-let [page @(rf/subscribe [:common/page])]
       (let [page-prev @(rf/subscribe [:common/page :last])]
         [:main.main-content.perspective-top
          {:id "main"
           :class (when-not ext-back? "animate")}
          [swapper swap-class
           [ui/safe :page [page]]
           (when page-prev
             [ui/safe :page-prev [page-prev]])]])
       [ui/loading-spinner true :massive])) ; removed since jars now that have hero in original html

   [common/footer-full @(rf/subscribe [:content [:footer]])]
   [common/footer @(rf/subscribe [:content [:footer]])]
   [ui/safe :hud [ui/hud (rf/subscribe [:hud])]]
   [common/to-top]


(defn with-heading
  [heading-path component & [override]]
  [:<>
   [ui/fading-bg-heading (merge @(rf/subscribe [:content heading-path])
                                  override)]
   component])

(defn search-ui
  []
  [search/ui "blog-posts"])

(defn test-page []
  [with-heading [:common :banner-heading]
   (let [routes {:parallax experiment/parallax
                 :model-viewer experiment/model-viewer
                 :codemirror experiment/code-mirror
                 :leaflet experiment/leaflet
                 ; :leaflet-2 experiment/leaflet-react-component
                 :search search-ui
                 :broken [:div]}
          tab @(rf/subscribe [:state [:experiments]])]
     [:section.experiments.solid-bg.fullwide.noborder
      [:ul.tabs-container.flex
       (for [tab-key (keys routes)] ^{:key tab-key}
         [:li [:a {:href @(rf/subscribe [:href :test-tab {:tab tab-key}])}
               [:button {:class (if (= tab tab-key) "bottomborder" "topborder")}
               tab-key]]])]
      [ui/safe :experiments [(tab routes)]]])
    {:title "Experiments" :tint "green"}])

(defn doc-page []
  [with-heading [:docs :heading]
   [:section.docs.solid-bg.hi-z.noborder.fullwide
    [docs/page]]])

(defn blog-page []
  [with-heading [:blog :heading] [blog/blog-container [blog/blog-feed]]])
(defn post-blog-page [] ; how nicely set is-personal for this but also unset etc yada
  [with-heading [:blog :heading] [blog/post-blog]])
(defn blog-archive-page []
  [with-heading [:blog :heading] [blog/blog-archive]])
(defn blog-tag-page []
  [with-heading [:blog :heading] [blog/blog-tag-view]])
(defn blog-post-page []
  [with-heading [:blog :heading] [blog/blog-single-post]])

(defn cv-page []
  [with-heading [:cv :heading] [cv/cv]])

(defn log-page []
  [with-heading [:common :banner-heading]
   [ui/log (rf/subscribe [:option [:log]])
    (rf/subscribe [:get :diagnostics])]
   {:title "Log" :tint "blue"}])

(defn not-found-page []
  [with-heading [:common :banner-heading]
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
       :controllers [{:start (fn [{:keys [path]}]
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
     ["cv"
      {:name        :cv
       :view        #'cv-page
       :controllers [{:stop (fn [_]
                              (rf/dispatch [:state [:fullscreen :cv] false]))}]}]
     ["hire"
      {:name        :hire
       :view        #'view/ui-auto
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "bottom" 700]))}]}]
     ["docs" {:controllers
              [{:start (fn [_]
                         (rf/dispatch [:docs/init]))}]}
      ["" {:name :docs
           :view #'doc-page
           :controllers
           [{:start (fn [_])}]}]
      ["/codox/:doc"
       {:name :docs-codox-page
        :view #'doc-page
        :controllers
        [{:parameters {:path [:doc]}
          :start (fn [{:keys [path]}]
                   (rf/dispatch [:docs/get (:doc path)])
                   (rf/dispatch [:docs/set-page (:doc path)]))}]}]]
     
     ["blog" {:controllers [{:start (fn [_]
                                      (rf/dispatch [:on-booted :firebase [:blog/init]])
                                      (rf/dispatch [:blog/set-posts-per-page 3]))}] } ; should also allow 0 = infinite scroll
      [""     {:name :blog
               :view #'blog-page
               :controllers [{:start (fn [_]
                                       (rf/dispatch [:blog/nav-page 1]) ; down here so back-btn works from page/2 to blog aka page/1.
                                       (rf/dispatch [:->css-var! "line-width" "1px"]); TODO fix so does this without hardcoding either. Might also set line-color to something less pronounced.
                                       (rf/dispatch [:->css-var! "line-width-vert" "1px"]))
                              :stop (fn []
                                       (rf/dispatch [:->css-var! "line-width" "2px"])
                                       (rf/dispatch [:->css-var! "line-width-vert" "2px"]))}]}] ; needed here so going back from blog/page/2 to blog returns one to page 1...
      ["/page/:nr"
       {:name :blog-page
        :view #'blog-page
        :controllers
        [{:parameters {:path [:nr]}
          :start (fn [{:keys [path]}]
                   (rf/dispatch [:blog/nav-action (:nr path)]))
          :stop (fn [{:keys [path]}])}]}]
      ["/post/:permalink"
       {:name :blog-post
        :view #'blog-post-page
        :controllers
        [{:parameters {:path [:permalink]}
          :start (fn [{:keys [path] :as data}]
                   (let [id (-> path :permalink (string/split "-") last js/parseInt)]
                     (rf/dispatch [:blog/state [:current-post-id] id])
                     #_(rf/dispatch [:common/set-title "Blog post title: not implemented"])))
          :stop (fn [{:keys [path] :as data}]
                  (rf/dispatch [:blog/state [:current-post-id] nil])
                  (rf/dispatch [:common/set-title nil]))}]}]
      ["/archive" {:name :blog-archive
                   :view #'blog-archive-page}]
      ["/tag/:tag" {:name :blog-tag
                   :view #'blog-tag-page
                   :controllers
                   [{:parameters {:path [:tag]}
                     :start (fn [{:keys [path]}]
                              (rf/dispatch [:blog/state [:viewing-tag] (:tag path)]))
                     :stop (fn [{:keys [path]}]
                             (rf/dispatch [:blog/state [:viewing-tag] nil]))}]}]]
     ["post-blog" {:name :post-blog
                   :view #'post-blog-page
                   :controllers [{:start (fn [_] (rf/dispatch [:blog/init-posting]))}
                                 {:stop (fn [_] (rf/dispatch [:blog/cancel-edit]))}]}]
     ["log" {:name :log
              :view #'log-page}]
     ["test" 
      ["" {:name :test
           :view #'test-page}]
      ["/:tab"
       {:name :test-tab
        :view #'test-page
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
    (rf/dispatch-sync [:common/navigate match]) ; -sync avoids not having route when components mount
    (do
       (rf/dispatch [:diag/new :error "404" (str "Not found")])
       (rf/dispatch [:state [:error-page] not-found-page]))))

(defn ignore-anchor-click?
  [router e el uri]
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
(defn render []
  (reset! root
    (rdomc/create-root (.getElementById js/document "app")))
  (rdomc/render @root [#'page]))

(defn mount-components "Called each update when developing" []
  (rf/clear-subscription-cache!)
  (start-router!) ; restart router on reload?
  (rf/dispatch [:reloaded])
  (util/log "Mounting root component")
  (render)
  #_(rdom/render [#'page] (.getElementById js/document "app")))

(defn init "Called only on page load" []
  (rf/dispatch-sync [:init-db])
  (rf/dispatch-sync [:fb/fetch-settings]) ;sync because number of early fetches depend on this...
  (rf/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
  (ajax/load-interceptors!)
  (mount-components)
  (rf/dispatch [::bp/set-breakpoints
                :breakpoints [:mobile 560
                              :tablet 992
                              :small-monitor 1200
                              :large-monitor]
                :debounce-ms 250]) ;; optional
  (rf/dispatch [:ls/get-path [:cv-visited] [:state :cv :visited]])
  (rf/dispatch [:cookie/show-notice]) ; do later first check if prev visit
  ; (rf/dispatch [:on-booted :firebase [:init]])
  (rf/dispatch [:on-booted :firebase [:init/cms]])
  (rf/dispatch [:on-booted :firebase [:init/imagor]])
  (js/setTimeout #(rf/dispatch [:init])
                 100)
  #_(js/setTimeout (fn [] (doseq [evt [; temp crapfix, running this too early suddenly results in... nothing. what.

                               [:init/scroll-storage]
                               [:listener/popstate-back]
                               [:listener/scroll-direction]
                               [:listener/visibility-change]
                               ; [:on-booted :firebase [:id-counters/fetch]]
                               [:ls/get-path [:form-field] [:state :form-field]] ; restore any active form-fields
                               [:booted :site]]]
                    (rf/dispatch evt))
                   (util/log "Site init complete"))
                 3000)) ; listeners and stuff that might depend on being mounted

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?

; (init!)
