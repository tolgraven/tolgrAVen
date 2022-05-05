(ns tolgraven.core
  (:require
    [reagent.dom :as rdom]
    [reagent.core :as r]
    [re-frame.core :as rf]
    ; [kee-frame.core :as k]
    [com.degel.re-frame-firebase :as firebase]

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
    [tolgraven.cv.views :as cv]
    [tolgraven.experiments :as experiment]
    [reitit.core :as reitit]
    [reitit.frontend.history :as rfh]
    [reitit.frontend.easy :as rfe]
    [clojure.string :as string]
    [clojure.pprint :refer [pprint]])
  (:import goog.History))

(defn swapper "Swap between outgoing and incoming page view"
  [class comp-in comp-out]
  (let [swap (rf/subscribe [:state [:swap]])
        curr-page (-> (rf/subscribe [:common/route ]) deref :data :name)
        prev-page (-> (rf/subscribe [:common/route :last]) deref :data :name)
        force? (empty? (seq class))] ; no transition
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
          comp-out)])]))
;; ^^ this works but obviously should be going back and forth between two "equal" comps
;; that are therefore not being reloaded on leaving...


(defn page "Render active page inbetween header, footer and general stuff." []
  [:<>
   [ui/safe :header [common/header @(rf/subscribe [:content [:header]])]] ;TODO smooth transition to personal
   [:a {:name "linktotop" :id "linktotop"}]
   
   [ui/zoom-to-modal :fullscreen]
   [ui/safe :user [user/user-section @(rf/subscribe [:user/active-section])]]
   
   (if-let [error-page @(rf/subscribe [:state [:error-page]])] ; TODO any time do nav or like trigger :is-loading, start timer, if not flag done set within timeout, also error
     [:main.main-content.perspective-top
      [error-page]]
     (if-let [page @(rf/subscribe [:common/page])]
       (let [page-prev @(rf/subscribe [:common/page :last])
             anim-class (cond
                         (= (get-in @(rf/subscribe [:common/route]) [:data :name])
                            (get-in @(rf/subscribe [:common/route :last]) [:data :name])) ""
                         (= js/window.performance.navigation.type 2) "" ;doesnt ever hit 2, i guess because we hijackin?
                         @(rf/subscribe [:state [:browser-nav :got-nav]]) ""
                         :else "opacity")] ; should be dep on, nav same as last no anim, nav by history no anim...
         [:main.main-content.perspective-top
          {:id "main"}
          [swapper anim-class
           [ui/safe :page [page]]
           (when page-prev
             [ui/safe :page-prev [page-prev]])]])
       [ui/loading-spinner true :massive]))

   [common/footer @(rf/subscribe [:content [:footer]])]
   [ui/safe :hud [ui/hud (rf/subscribe [:hud])]]
   [common/to-top]
   [:a {:name "bottom" :id "bottom"}]])


(defn with-heading
  [heading-path component & [override]]
  [:<>
   [ui/fading-bg-heading (merge @(rf/subscribe [:content heading-path])
                                  override)]
   component])

(defn test-page []
  [with-heading [:common :banner-heading]
   (let [routes {:parallax experiment/parallax
                 :codemirror experiment/code-mirror
                 :leaflet experiment/leaflet
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
  (let [docs @(rf/subscribe [:content [:docs]])]
    [with-heading [:docs :heading]
     [:section.docs.solid-bg.hi-z.noborder
      {:ref #(when % (util/run-highlighter! "pre" %))} ; very nice way to get a did-mount
      [ui/md->div (:md docs)]]]))


(defn blog-page []
  [with-heading [:blog :heading] [blog/blog-container [blog/blog-feed]]])
(defn post-blog-page [] ; how nicely set is-personal for this but also unset etc yada
  [with-heading [:blog :heading] [blog/post-blog]])
(defn blog-archive-page []
  [with-heading [:blog :heading] [blog/blog-archive]])
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
     {:controllers [{:parameters {:query [:userBox]} ; ok so this how done. but surely will get unwieldy af?
                     :start (fn [{:keys [query]}]    ; and how get to update url with changes...
                              (util/log "start / controller" )
                              (case (:userBox query) ; was thinking "have :user/open-ui passing true/false and no case but would be spammy"
                               "true" (rf/dispatch [:user/open-ui])
                               ("false" nil) (rf/dispatch [:user/close-ui]))) ; well this being on start it wouldn't be open anyways
                     :stop (fn [{:keys [query]}]    ; why is this being run without leaving page?
                             (util/log "stop / controller"))}]}
     [""
      {:name        :home
       :view        #'view/ui
       :controllers [{:start (fn [{:keys [path]}]
                               (rf/dispatch [:state [:is-personal] false])
                               (rf/dispatch [:page/init-home]))}]}]
     ["about"
      {:name        :about
       :view        #'view/ui
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "about" 700]))}]}]
     ["services"
      {:name        :services
       :view        #'view/ui
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "main" 700]) ; hack because stickied so "already there"...
                               (rf/dispatch [:scroll/to "section-services" 1300]))}]}]
     ["cv"
      {:name        :cv
       :view        #'cv-page}]
     ["hire"
      {:name        :hire
       :view        #'view/ui
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "bottom" 700]))}]}]
     ["docs" {:name :docs
               :view #'doc-page
               :controllers [{:start (fn [_] (doall (map rf/dispatch [[:page/init-docs]
                                                                      [:state [:is-personal] true]])))}]}] ; really overkill this triggers each time. gotta be built-in solution somewhere? else work around
     ["blog" {:controllers [{:start (fn [_] (rf/dispatch [:on-booted :firebase [:blog/init]]))}] }
      [""     {:name :blog
               :view #'blog-page
               :controllers [{:start (fn [_] (rf/dispatch [:blog/set-posts-per-page 3]) ; should also allow 0 = infinite scroll
                                             (rf/dispatch [:blog/nav-page 1]))}]}] ; needed here so going back from blog/page/2 to blog returns one to page 1...
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
          :start (fn [{:keys [path]}]
                   (let [id (-> path :permalink (string/split "-") last js/parseInt)]
                     (rf/dispatch [:blog/state [:current-post-id] id])))
          :stop (fn [{:keys [path]}]
                  (js/console.log "stop" "blog-post controller" (:permalink path)))}]}]
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
                   :controllers [{:start (fn [_] (rf/dispatch [:blog/init-posting]))}]}]        
     ["log" {:name :log
              :view #'log-page}]
     ["test" 
      ["" {:name :test
           :view #'test-page}]
      ["/:tab"
       {:name :test-tab
        :view #'test-page
       :controllers [{:parameters {:path [:tab]}
                      :start (fn [{:keys [path]}]
                               (rf/dispatch [:state [:experiments] (keyword (:tab path))])
                               (rf/dispatch [:exception [:experiments] nil]))}]}]]
     ["not-found" {:name :not-found
                   :view #'not-found-page}]
     {:data {:controllers [{:start (util/log :debug "start" "root-controller")
                            :stop  (util/log :debug "stop" "root controller")}]}}]))

(defn on-nav [match history]
  ; (js/console.log match)
  (if match ; cant do fallback route in router apparently, but we get nil matches so can use that
    (rf/dispatch [:common/navigate match])
    (if true #_(util/elem-by-id )
      (do
       (rf/dispatch [:diag/new :error "404" (str "Not found")])
       ; (rf/dispatch [:common/navigate! :not-found]) ;TODO should provide some context. sought url etc. also shan't redirect dammit
       (rf/dispatch [:state [:error-page] not-found-page])))))

(defn ignore-anchor-click?
  [router e el uri]
  ; (js/console.log (.-fragment_ uri))
  (rf/dispatch-sync [:state [:fragment] (.-fragment_ uri)])
  (and #_:identical-uri ; cause pollutes history with duplicates
       #_:fragments-at-other-base-paths
       (rfh/ignore-anchor-click? router e el uri)))

(def router-settings
  {:use-fragment false ;doesnt do nuffin without (tho still takes over) so dunno point?
   :ignore-anchor-click?  ignore-anchor-click?
  })


(defn start-router! []
  (rfe/start! router on-nav router-settings))


;; -------------------------
;; Initialize app
(defn mount-components "Called each update when developing" []
  (rf/clear-subscription-cache!)
  (start-router!) ; restart router on reload?
  (rf/dispatch [:reloaded])
  (util/log "Mounting root component")
  (rdom/render [#'page] (.getElementById js/document "app")))


(defn init "Called only on page load" []
  (rf/dispatch-sync [:init-db])
  (rf/dispatch-sync [:fb/fetch-settings]) ;sync because number of early fetches depend on this...
  (ajax/load-interceptors!)
  (rf/dispatch [:cookie/show-notice]) ; do later first check if prev visit
  (util/log "Init complete")
  (mount-components)
  (rf/dispatch [:init])) ; listeners and stuff that might depend on being mounted

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?
