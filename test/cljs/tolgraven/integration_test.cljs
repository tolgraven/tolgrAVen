(ns tolgraven.integration-test
  (:require
    [cljs.test :refer-macros [deftest is testing async]]
    [re-frame.core :as rf]
    [re-frame.db :as rfdb]
    [reagent.ratom :as ratom]
    [reagent.core :as r]
    [reagent.dom.client :as dom]
    [reagent.dom.server :as server]
    [react-dom :as react-dom]
    [tolgraven.supabase.client :as supabase]
    [tolgraven.supabase.query :as supabase-query]
    [tolgraven.store.contract :as store-contract]
    [reitit.core :as reitit]
    [reitit.frontend.easy :as rfe]
    [tolgraven.routes :as routes]
    [tolgraven.loader :as loader]
    [tolgraven.views-common :as common]
    [tolgraven.views.page :as page]
    [tolgraven.component.data :as data]
    [tolgraven.component.sources]
    [tolgraven.docs.views :as docs-view]
    [tolgraven.ui.code :as code]
    [tolgraven.components.init :as init-view]
    [tolgraven.react :as shim]
    [re-frame.registrar :as registrar]
    [tolgraven.ssr.client :as ssr]
    [tolgraven.events]
    [tolgraven.subs]
    [shadow.lazy :as lazy]
    [tolgraven.search.subs :as search]
            ;; Browser tests bundle all module specs so ready-module initialization is exercised.
    [tolgraven.blog.module]
    [tolgraven.blog.model :as blog-model]
    [tolgraven.blog.views :as blog-views]
    [tolgraven.link-preview.module]
    [tolgraven.cv.module]
    [tolgraven.docs.module]
    [tolgraven.search.module]
    [tolgraven.user.module]
    [tolgraven.chat.module]
    [tolgraven.github.module]
    [tolgraven.gpt.module]
    [tolgraven.strava.module]
    [tolgraven.instagram.module]
    [tolgraven.experiments]))

(deftest route-matching
  (doseq [[path route-name module]
          [["/" :home nil] ["/about" :about nil] ["/services" :services nil]
           ["/hire" :hire nil] ["/cv" :cv :cv] ["/docs" :docs :docs]
           ["/docs/codox/index" :docs-codox-page :docs]
           ["/blog" :blog :blog] ["/blog/page/2" :blog-page :blog]
           ["/blog/post/example-1" :blog-post :blog]
           ["/blog/tag/clojure" :blog-tag :blog]
           ["/blog/archive" :blog-archive :blog]
           ["/blog/new-post" :new-post :blog]
           ["/test/search" :test-tab :test]
           ["/client-oauth/twitter" :client-oauth-twitter nil]
           ["/not-found" :not-found nil]]]
    (let [data (:data (reitit/match-by-path routes/router path))]
      (is (= route-name (:name data)) path)
      (is (= module (:module data)) path)
      (when module
        (is (nil? (:view data)) "Module views stay out of eager route data"))))
  (is (= "/blog/new-post"
         (:path (reitit/match-by-name routes/router :new-post))))
  (is (nil? (reitit/match-by-path routes/router "/unknown"))))

(deftest late-module-response-does-not-replace-current-page
  (async done
    (let [*events (atom [])
          *navigation (atom 0)
          *resolve (atom nil)
          pending (js/Promise. (fn [resolve _] (reset! *resolve resolve)))
          dispatch! #(swap! *events conj %)
          old-match {:data {:name :cv :module :cv :page :page}}
          current-view (fn [] [:div "Current page"])
          current-match {:data {:name :home :view current-view}}]
      (routes/navigate! *navigation dispatch! (constantly pending) old-match)
      (routes/navigate! *navigation dispatch! (constantly pending) current-match)
      (@*resolve {:view {:page (fn [] [:div "Old page"])}})
      (js/setTimeout
        (fn []
          (let [navigations (filter #(= :common/navigate (first %)) @*events)]
            (is (= [current-match] (mapv second navigations)))
            (is (some #(= [:loading/off :page 1] %) @*events))
            (done)))
        0))))

(deftest hydrated-route-does-not-enable-page-spinner
  (async done
    (let [before @ssr/*snapshot
          *events (atom [])
          *loads (atom 0)]
      (reset! ssr/*snapshot {:path "/blog" :kind :blog})
      (try
        (routes/navigate! (atom 0) #(swap! *events conj %)
                          (fn [_] (swap! *loads inc)
                            (js/Promise.resolve {:view {:page (fn [] [:div])}}))
                          {:path "/blog" :data {:name :blog :module :blog :page :page}})
        (is (= 1 @*loads) "Hydration still initializes the module")
        (is (not-any? #(= :loading/on (first %)) @*events))
        (finally (reset! ssr/*snapshot before)))
      (js/setTimeout done 0))))

(deftest module-load-failure-clears-loading
  (async done
    (let [*events (atom [])]
      (routes/navigate! (atom 0) #(swap! *events conj %)
                        (fn [_] (js/Promise.reject (js/Error. "Test load failure")))
                        {:data {:name :docs :module :docs :page :page}})
      (js/setTimeout
        (fn []
          (is (some #(= [:loading/off :page 1] %) @*events))
          (is (some #(= :diag/new (first %)) @*events))
          (let [fallback (some #(when (= [:error-page] (second %)) (nth % 2)) @*events)
                form (fallback)]
            (is (= "This page could not be loaded" (:title (nth form 3))))
            (is (fn? (last form)) "Page failures offer retry instead of a misleading 404"))
          (done))
        0))))

(deftest cached-modules-initialize-once-and-return-native-promises
  (async done
    (let [id (keyword (str (random-uuid)))
          *initializations (atom 0)
          spec {:view {:page (fn [] [:div])}
                :init #(swap! *initializations inc)}
          loadable (reify
                     lazy/ILoadable (ready? [_] true)
                     IDeref (-deref [_] spec))]
      (with-redefs [loader/modules {id loadable}]
        (let [first-load (loader/load! {:module id})
              second-load (loader/load! {:module id})]
          (is (instance? js/Promise first-load))
          (is (fn? (.-finally second-load)))
          (-> (js/Promise.all #js [first-load second-load])
              (.then (fn [results]
                       (is (= 1 @*initializations))
                       (is (= spec (aget results 0) (aget results 1)))))
              (.catch (fn [error] (is false (str error))))
              (.finally done)))))))

(deftest ready-module-waits-for-declared-data
  (let [id (keyword (str (random-uuid)))
        before @rfdb/app-db
        spec {:depends [{:source :app-db :path [id]}]
              :view {:view (fn [] [:div "Ready"])}}
        loadable (reify
                   lazy/ILoadable (ready? [_] true)
                   IDeref (-deref [_] spec))]
    (try
      (with-redefs [loader/modules {id loadable}]
        (is (nil? (loader/ready-spec id :view [])))
        (swap! rfdb/app-db assoc id false)
        (is (= spec (loader/ready-spec id :view []))
            "A present false value is loaded data, not a loading state"))
      (finally (reset! rfdb/app-db before)))))

(deftest component-loader-forwards-initialization-hooks-and-args
  (async done
    (let [id (keyword (str (random-uuid)))
          *calls (atom [])
          *events (atom [])
          *resolve-post (atom nil)
          posted (js/Promise. (fn [resolve _] (reset! *resolve-post resolve)))
          component (fn [& _] [:div "Loaded"])
          module-spec {:view {:view component}
                       :init (fn [& args] (swap! *calls conj [:init args]))}
          loadable (reify
                     lazy/ILoadable (ready? [_] true)
                     IDeref (-deref [_] module-spec))
          render (loader/make-browser-render)
          spec {:module id
                :init-evt [:test/init id]
                :pre-fn (fn [& args] (swap! *calls conj [:pre args]))
                :post-fn (fn [loaded & args]
                           (swap! *calls conj [:post loaded args])
                           (@*resolve-post nil)
                           ;; A hook result must not replace the module used to render.
                           :hook-result)}]
      (-> (js/Promise.resolve nil)
          (.then (fn []
                   (with-redefs [loader/modules {id loadable}
                                 rf/subscribe (fn ([_] (atom true))
                                                  ([_ _] (atom true)))
                                 rf/dispatch #(swap! *events conj %)]
                     (is (= [component :first {:second true}]
                            (last (render spec :first {:second true})))
                         "Ready modules render on the first pass, before promise callbacks"))
                   (is (= [[:test/init id]] @*events))
                   posted))
          (.then (fn []
                   (is (= [[:pre [:first {:second true}]]
                           [:init [:first {:second true}]]
                           [:post module-spec [:first {:second true}]]]
                          @*calls))
                   (with-redefs [rf/subscribe (fn ([_] (atom true))
                                                 ([_ _] (atom true)))]
                     (is (= [component :first {:second true}]
                            (last (render spec :first {:second true})))))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (swap! loader/*loads dissoc id)
                      (done)))))))

(deftest deferred-component-preserves-scope-arguments
  (let [*events (atom [])
        before (fn [& _] [:button "Load"])
        render (loader/make-browser-render)]
    (with-redefs [rf/subscribe (fn ([_] (atom false))
                                 ([_ _] (atom false)))
                  rf/dispatch #(swap! *events conj %)]
      (let [[_ attrs content] (render {:module :search :<before> before}
                                     "blog-posts")]
        (testing "The placeholder and scope request both receive the component args"
          (is (= [before "blog-posts"] content))
          ((:on-click attrs))
          (is (= [[:scope/init :search '("blog-posts")]] @*events)))))))

(deftest literal-search-completions
  (doseq [query ["C++" "[x]" "a.b" "(fn"]]
    (let [result (search/autocomplete-suggestion
                   query {:highlights [{:snippet (str "Try <mark>" query "</mark> next")}]
                          :text_match 10})]
      (is (= (str query " next") (:text result)))))
  (is (nil? (search/autocomplete-suggestion "missing" {:highlights [{:snippet "Other text"}]})))
  (is (nil? (search/autocomplete-suggestion "query" {}))))

(deftest active-profile-merges-live-public-fields-with-private-details
  (let [before @rfdb/app-db
        public (ratom/atom {:id "u" :data {:id "u" :name "Before" :karma 9}})]
    (try
      (rf/clear-subscription-cache!)
      (swap! rfdb/app-db assoc-in [:state :booted :store] true)
      (swap! rfdb/app-db assoc-in [:state :active-user]
             {:id "u" :name "Private snapshot" :karma 1 :roles ["admins"] :comment-votes {:c 1}})
      (with-redefs [supabase/ensure-query! (fn [_] (ratom/make-reaction #(deref public)))]
        (let [active (rf/subscribe [:user/active-user])]
          (is (= 9 (:karma @active)))
          (is (= ["admins"] (:roles @active)))
          (reset! public {:id "u" :data {:id "u" :name "After" :karma 10}})
          (ratom/flush!)
          (is (= "After" (:name @active)))
          (is (= 10 (:karma @active)))
          (is (= {:c 1} (:comment-votes @active)))))
      (finally (rf/clear-subscription-cache!) (reset! rfdb/app-db before)))))


(deftest streamed-blog-posts-and-comment-threads
  (let [before @rfdb/app-db
        post (fn [id] {:id id :doc_id (str id) :title (str "Post " id)
                       :text "Post body" :tags "test" :ts id})
        *seed (ratom/atom
                {:blog_posts (mapv post [1 24 28])
                 :blog_comments [{:id "root" :parent_post 24 :text "Root comment" :ts 1}
                                 {:id "reply" :parent_post 24 :parent_comment "root"
                                  :text "Nested reply" :ts 2}
                                 {:id "other" :parent_post 28 :text "Other post" :ts 3}]})]
    (try
      (rf/clear-subscription-cache!)
      (swap! rfdb/app-db assoc-in [:state :booted :store] true)
      (with-redefs [supabase/ensure-query!
                    (fn [opts]
                      (ratom/make-reaction
                        #(supabase-query/query-contract
                           (store-contract/seed->contract @*seed) opts)))]
        (let [ids (rf/subscribe [:blog/post-ids])
              first-page (rf/subscribe [:blog/ids-for-page 0 2])
              second-page (rf/subscribe [:blog/ids-for-page 1 2])
              current-post (rf/subscribe [:blog/post 24])
              roots (rf/subscribe [:comments/for-q-flat 24])
              replies (rf/subscribe [:comments/for-q-flat 24 "root"])]
          (testing "Native numeric Supabase IDs drive paging and permalink lookup"
            (is (= [28 24 1] @ids))
            (is (= [28 24] @first-page))
            (is (= [1] @second-page))
            (is (= 3 @(rf/subscribe [:blog/count])))
            (is (= "Post 24" (:title @current-post)))
            (is (= 28 @(rf/subscribe [:blog/adjacent-post-id :prev 24])))
            (is (= 1 @(rf/subscribe [:blog/adjacent-post-id :next 24]))))
          (testing "Root comments and replies stay in their own post and thread"
            (is (= #{:root} (set (keys @roots))))
            (is (= "Root comment" (get-in @roots [:root :text])))
            (is (= #{:reply} (set (keys @replies))))
            (is (= "Nested reply" (get-in @replies [:reply :text]))))
          (testing "Streamed inserts and deletes update the same subscriptions"
            (swap! *seed update :blog_posts conj (post 29))
            (swap! *seed update :blog_comments conj
                   {:id "new-reply" :parent_post 24 :parent_comment "root"
                    :text "Live reply" :ts 4})
            (ratom/flush!)
            (is (= [29 28 24 1] @ids))
            (is (= [29 28] @first-page))
            (is (= #{:reply :new-reply} (set (keys @replies))))
            (swap! *seed update :blog_posts #(filterv (fn [p] (not= 29 (:id p))) %))
            (ratom/flush!)
            (is (= [28 24 1] @ids)))))
      (finally
        (rf/clear-subscription-cache!)
        (reset! rfdb/app-db before)))))

(deftest active-vote-remains-clickable-unless-write-is-pending
  (let [pending (ratom/atom false)
        element (.createElement js/document "div")
        root (dom/create-root element)]
    (with-redefs [rf/subscribe (fn
                                ([[event]] (if (= :blog/vote event) (ratom/atom :up) pending))
                                ([_ _] pending))]
      (try
        (react-dom/flushSync #(dom/render root [blog-views/<vote-btn> {:user "author" :active-user "voter" :path [1 "c"] :vote :up}]))
        (is (false? (.-disabled (.querySelector element "button"))))
        (reset! pending true)
        (react-dom/flushSync #(r/flush))
        (is (true? (.-disabled (.querySelector element "button"))))
        (finally (react-dom/flushSync #(dom/unmount root)))))))


(deftest blog-navigation-rejects-invalid-pages-and-page-sizes
  (let [before @rfdb/app-db]
    (try
      (doseq [number [nil "" "invalid" "2oops" "0" "-1" -2 js/NaN 1.5]]
        (rf/dispatch-sync [:blog/nav-page number])
        (is (= 0 (get-in @rfdb/app-db [:state :blog :page]))))
      (rf/dispatch-sync [:blog/nav-page "3"])
      (is (= 2 (get-in @rfdb/app-db [:state :blog :page])))
      (rf/dispatch-sync [:blog/set-posts-per-page 0])
      (is (= 1 (get-in @rfdb/app-db [:options :blog :posts-per-page])))
      (is (nil? (blog-model/page-ids [3 2 1] -1 2)))
      (is (nil? (blog-model/page-ids [3 2 1] 0 0)))
      (is (= [] (blog-model/page-ids [3 2 1] 9 2)))
      (finally (reset! rfdb/app-db before)))))

(deftest blog-tags-handle-missing-values-whitespace-and-sequences
  (is (= [] (blog-model/tags nil)))
  (is (= ["clojure" "web"] (blog-model/tags "  clojure\tweb  clojure ")))
  (is (= ["web"] (blog-model/tags [nil "" " web " "web"]))))

(deftest blog-feed-keeps-post-subscriptions-reactive-after-initial-loading
  (let [element (.createElement js/document "div") root (dom/create-root element)
        *post (r/atom nil)]
    (.appendChild (.-body js/document) element)
    (with-redefs [rf/subscribe (fn ([query]
                                (case (first query)
                                  :blog/count (r/atom 1)
                                  :blog/posts-per-page (r/atom 3)
                                  :blog/nav-page (r/atom 0)
                                  :blog/ids-for-page (r/atom [42])
                                  :blog/post *post))
                                ([query _] (rf/subscribe query)))
                  blog-views/<blog-post> (fn [{:keys [post]}] [:p (or (:text post) "Loading")])
                  blog-views/<blog-nav> (fn [_] nil)]
      (try
        (react-dom/flushSync #(dom/render root [blog-views/<blog-feed>]))
        (is (= "Loading" (.-textContent element)))
        (reset! *post {:id 42 :text "Loaded asynchronously"})
        (react-dom/flushSync #(r/flush))
        (is (= "Loaded asynchronously" (.-textContent element)))
        (reset! *post {:id 42 :text "Live edit"})
        (react-dom/flushSync #(r/flush))
        (is (= "Live edit" (.-textContent element)))
        (finally (react-dom/flushSync #(dom/unmount root)) (.remove element))))))

(deftest header-routes-survive-json-roundtrip-on-direct-blog-load
  (let [before @rfdb/app-db
        element (.createElement js/document "div") root (dom/create-root element)
        menu {:work [["Services" "/services" "services"]
                     ["Story" "/about" "about"]
                     ["Hire me" "/hire" "hire"]]
              :personal [["Blog" "/blog" "blog"]]}]
    (try
      (rf/clear-subscription-cache!)
      (swap! rfdb/app-db assoc :common/route (reitit/match-by-path routes/router "/blog"))
      (with-redefs [rfe/href (fn
                              ([route] (:path (reitit/match-by-name routes/router route)))
                              ([route _params _query]
                               (:path (reitit/match-by-name routes/router route))))]
        (react-dom/flushSync #(.render root (r/as-element [common/<header-nav> menu])))
        (is (= ["/services" "/about" "/hire" "/blog"]
               (mapv #(.getAttribute % "href") (array-seq (.querySelectorAll element "a"))))))
      (finally (react-dom/flushSync #(dom/unmount root))
               (rf/clear-subscription-cache!) (reset! rfdb/app-db before)))))


(deftest fragment-links-retain-current-route-parameters
  (let [before @rfdb/app-db
        *calls (atom [])]
    (try
      (rf/clear-subscription-cache!)
      (swap! rfdb/app-db assoc :common/route
             (reitit/match-by-path routes/router "/blog/post/A-new-era-28"))
      (with-redefs [rfe/href (fn [route params _query]
                              (swap! *calls conj [route params])
                              "/blog/post/A-new-era-28")]
        (is (= "/blog/post/A-new-era-28#main" @(rf/subscribe [:href "#main"])))
        (is (= [[:blog-post {:permalink "A-new-era-28"}]] @*calls)))
      (finally (rf/clear-subscription-cache!) (reset! rfdb/app-db before)))))

(deftest shim-keeps-registration-call-site-in-debug-builds
  (shim/reg-event-db :test/instrumented-shim (fn [db _] db))
  (try
    (let [source (meta (registrar/get-handler :event :test/instrumented-shim))]
      (is (re-find #"integration_test.cljs" (:file source)))
      (is (pos? (:line source))))
    (finally (rf/clear-event :test/instrumented-shim))))

(deftest bootstrap-fallback-follows-state-and-retries-without-owning-page-dom
  (let [before @rfdb/app-db
        element (.createElement js/document "div")
        retained (.createElement js/document "article")
        root (dom/create-root element)
        *retries (atom 0)
        retry! #(do (swap! *retries inc)
                    (rf/dispatch-sync [:state [:page-init] {:status :loading}]))]
    (.appendChild (.-body js/document) retained)
    (.appendChild (.-body js/document) element)
    (try
      (react-dom/flushSync #(do
                             (rf/dispatch-sync [:state [:page-init] {:status :failed}])
                             (dom/render root [init-view/<fallback> retry!])))
      (is (some? (.querySelector element "[role=alert]")))
      (react-dom/flushSync #(.click (.querySelector element "button")))
      (r/flush)
      (is (= 1 @*retries))
      (is (nil? (.querySelector element "[role=alert]")))
      (is (.-isConnected retained) "Retry preserves the separate page root")
      (finally
        (dom/unmount root) (.remove element) (.remove retained)
        (rf/clear-subscription-cache!) (reset! rfdb/app-db before)))))


(deftest page-swap-retains-outgoing-dom-instead-of-remounting-it
  (let [before @rfdb/app-db element (.createElement js/document "div")
        root (dom/create-root element)
        a {:path "/"} b {:path "/blog"}
        *form (r/atom [page/<swapper> "opacity" [:div#kept-page "Home"] nil a nil])]
    (.appendChild (.-body js/document) element)
    (try
      (swap! rfdb/app-db assoc :common/route a :common/route-last nil)
      (react-dom/flushSync #(dom/render root [(fn [] @*form)]))
      (let [original (.querySelector element "#kept-page")]
        (swap! rfdb/app-db assoc :common/route b :common/route-last a)
        (reset! *form [page/<swapper> "opacity" [:div#incoming-page "Blog"] [:div#kept-page "Home"] b a])
        (r/flush)
        (is (identical? original (.querySelector element "#kept-page")))
        (is (some? (.querySelector element ".swapped #kept-page")))
        (is (some? (.querySelector element ".swap-in #incoming-page"))))
      (finally (dom/unmount root) (.remove element) (reset! rfdb/app-db before)))))

(deftest background-initialization-does-not-dispatch-loading-events
  (let [handler (tolgraven.events/get-http-fn :get)
        effects (handler {:db {}} [:http/get {:uri "/api/supabase/settings" :background? true}])]
    (is (not (contains? effects :dispatch)))
    (is (nil? (get-in effects [:http-xhrio :on-success 2])))
    (is (not (contains? (:http-xhrio effects) :background?)))))

(deftest subscription-preload-releases-only-its-own-reaction
  (async done
    (let [*ready (r/atom false) *disposed (atom 0)
          _ (rf/reg-sub-raw :test/preload-ready
              (fn [_ _] (ratom/make-reaction #(deref *ready)
                                           :on-dispose #(swap! *disposed inc))))
          consumer (ratom/make-reaction #(deref (rf/subscribe [:test/preload-ready])) :auto-run true)
          _ @consumer
          load! (:load! (get @data/*sources :subscription))]
      (-> (load! {:query [:test/preload-ready] :timeout-ms 100})
          (.then (fn [ready]
                   (is (true? ready))
                   (is (zero? @*disposed) "The mounted consumer still owns the shared subscription")
                   (is (true? @consumer))
                   (ratom/dispose! consumer)
                   (is (= 1 @*disposed) "Last owner releases the subscription")))
          (.catch #(is false (str %)))
          (.finally (fn [] (ratom/dispose! consumer) (done))))
      (reset! *ready true))))

(deftest codox-links-are-rewritten-before-rendering
  (is (= "<a href=\"/docs/codox/tolgraven.core#init\">Init</a>"
         (docs-view/page-links "<a href=\"tolgraven.core.html#init\">Init</a>")))
  (doseq [url ["https://example.com/page.html" "#heading" "/blog" "mailto:test@example.com"]]
    (let [html (str "<a href=\"" url "\">Link</a>")]
      (is (= html (docs-view/page-links html))))))

(deftest markdown-code-uses-reagent-props-without-js-conversion
  (is (= "<code>inline</code>" (server/render-to-static-markup [code/<markdown-code-component> {:children "inline"}]))))

(deftest debug-and-theme-events-preserve-app-db
  (let [before @rfdb/app-db]
    (try
      (reset! rfdb/app-db {:sentinel :retained})
      (rf/dispatch-sync [:debug [:layers] true])
      (rf/dispatch-sync [:theme/dark-mode true])
      (rf/dispatch-sync [:theme/colorscheme "test"])
      (is (= {:sentinel :retained
              :state {:debug {:layers true}}
              :options {:theme {:dark-mode true :colorscheme "test"}}}
             @rfdb/app-db))
      (finally (reset! rfdb/app-db before)))))

(deftest markdown-code-renders-through-the-react-adapter
  (let [element (.createElement js/document "div") root (dom/create-root element)]
    (try
      (react-dom/flushSync
       #(dom/render root [code/<parse-markdown-components> "Inline `hello`\n\n```clojure\n(+ 1 2)\n```\n"]))
      (is (.includes (.-textContent element) "hello"))
      (is (.includes (.-textContent element) "(+ 1 2)"))
      (is (some? (.querySelector element "pre code")))
      (finally (react-dom/flushSync #(dom/unmount root))))))

(defn- event-effects [id coeffects event]
  ;; Exercise the registered handler without running navigation/timer effects.
  (let [handler (-> (registrar/get-handler :event id) last :before)]
    (:effects (handler {:coeffects (assoc coeffects :event event)}))))

(deftest first-navigation-keeps-ssr-position-and-later-navigation-still-scrolls
  (let [match {:path "/blog/post/A-new-era-28" :data {:name :blog-post :view identity}}
        effects (event-effects :common/navigate
                               {:db {} :scroll-position 0 :id {:id {:navigations 0}}}
                               [:common/navigate match])
        scroll-event (some #(when (= :scroll/on-navigate (first (get-in % [1 :dispatch])))
                             (get-in % [1 :dispatch])) (:dispatch-n effects))]
    (is (= [:scroll/on-navigate (:path match) 0] scroll-event)
        "The injected ID counter marks the initial route as the first navigation")
    (let [initial (event-effects :scroll/on-navigate {:db {}} scroll-event)
          subsequent (event-effects :scroll/on-navigate {:db {}} [:scroll/on-navigate (:path match) 1])
          returning (event-effects :scroll/on-navigate
                                   {:db {:state {:browser-nav {:got-nav true}
                                                 :scroll-position {(:path match) 250}}}}
                                   [:scroll/on-navigate (:path match) 2])]
      (is (not-any? #(= :scroll/and-block (first %)) (:dispatch-n initial))
          "Hydration does not scroll an already visible page")
      (is (some #{[:scroll/and-block "main"]} (:dispatch-n subsequent))
          "Ordinary SPA navigation keeps its scroll-to-content behavior")
      (is (some #{[:scroll/and-block 250]} (:dispatch-n returning))
          "Browser back navigation still restores a saved position"))))
