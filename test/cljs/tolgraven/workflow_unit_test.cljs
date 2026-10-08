(ns tolgraven.workflow-unit-test
  "Isolated router, component and managed-source unit tests. External boundaries
   may be fake; live integration coverage is run against the real application."
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require
    [cljs.core.async]
    [ajax.core :as ajax]
    [tolgraven.docs.pages :as docs-pages]
    [tolgraven.test-support :as support]
    [cljs.test :refer-macros [deftest is testing async]]
    [re-frame.core :as rf]
    [reagent.ratom :as ratom]
    [tolgraven.test-support :refer [mount-subscriptions! subscription-value! state-at! wait-for!]]
    [re-frame.tooling :as tooling]
    [tolgraven.supabase.scoped :as scoped]
    [tolgraven.supabase-client-test :as mocks]
    [reagent.core :as r]
    [reagent.dom.client :as dom]
    [reagent.dom.server :as server]
    [tolgraven.supabase.client :as supabase]
    [tolgraven.supabase.query :as supabase-query]
    [tolgraven.store.contract :as store-contract]
    [reitit.core :as reitit]
    [reitit.frontend.easy :as rfe]
    [tolgraven.routes :as routes]
    [tolgraven.loader :as loader]
    [tolgraven.component :as component]
    [tolgraven.react :as react]
    [tolgraven.macros :as m :refer-macros [defpage]]
    [tolgraven.render-context :as context]
    [tolgraven.views-common :as common]
    [tolgraven.page-transition :as page-transition]
    [tolgraven.views.page :as page-view]
    [tolgraven.component.data :as data]
    [tolgraven.component.restore :as restore]
    [tolgraven.component.loading :as loading]
    [tolgraven.component.sources]
    [tolgraven.docs.views :as docs-view]
    [tolgraven.ui.code :as code]
    [tolgraven.ui :as ui]
    [tolgraven.components.init :as init-view]
    [tolgraven.react :as shim]
    [tolgraven.component.instrumentation :as instrumentation]
    [re-frame.registrar :as registrar]
    [tolgraven.ssr.client :as ssr]
    [tolgraven.events]
    [tolgraven.subs]
    [shadow.lazy :as lazy]
    [tolgraven.search.subs :as search]
    ;; Browser tests bundle all module specs so ready-module initialization is exercised.
    [tolgraven.blog.module]
    [tolgraven.blog.model :as blog-model]
    [tolgraven.blog.comments :as comments]
    [tolgraven.blog.views :as blog-views]
    [tolgraven.link-preview.module]
    [tolgraven.cv.module]
    [tolgraven.docs.module]
    [tolgraven.search.module]
    [tolgraven.user.module :as user-module]
    [tolgraven.chat.module]
    [tolgraven.github.module]
    [tolgraven.github.views :as github-views]
    [tolgraven.gpt.module]
    [tolgraven.strava.module]
    [tolgraven.instagram.module]
    [tolgraven.experiments]))
(defn- fake-scoped-transport!
  [*rows]
  ;; Only the external boundary is fake. Production managed readers still batch,
  ;; dispatch result events, populate app-db, and dispose with their consumers.
  (let [mock (mocks/mock-client)
        previous @scoped/*transport
        *reads (atom [])]
    (scoped/connect! (:sdk mock)
                     (fn [opts success _]
                       (swap! *reads conj opts)
                       (js/setTimeout #(success (supabase-query/query-contract
                                                  (store-contract/seed->contract @*rows)
                                                  opts))
                                      0)))
    {:reads *reads,
     :change! (fn [table]
                (when-let [callback (some-> (get @(:channels mock) (str "blog-scoped-" table))
                                            :change
                                            deref)]
                  (callback #js {}))),
     :close! #(scoped/connect! (:client previous) (:read! previous))}))
(deftest route-matching
  (doseq [[path route-name module]
            [["/" :home nil] ["/about" :about nil] ["/services" :services nil] ["/hire" :hire nil]
             ["/cv" :cv :cv] ["/docs" :docs :docs] ["/docs/codox/index" :docs-codox-page :docs]
             ["/blog" :blog :blog] ["/blog/page/2" :blog-page :blog]
             ["/blog/post/example-1" :blog-post :blog] ["/blog/tag/clojure" :blog-tag :blog]
             ["/blog/archive" :blog-archive :blog] ["/blog/new-post" :new-post :blog]
             ["/test/search" :test-tab :test] ["/client-oauth/twitter" :client-oauth-twitter nil]
             ["/not-found" :not-found nil]]]
    (let [data (:data (reitit/match-by-path routes/router path))]
      (is (= route-name (:name data)) path)
      (is (= module (:module data)) path)
      (when module (is (nil? (:view data)) "Module views stay out of eager route data"))))
  (is (= "/blog/new-post" (:path (reitit/match-by-name routes/router :new-post))))
  (is (nil? (reitit/match-by-path routes/router "/unknown"))))
(deftest late-module-response-does-not-replace-current-page
  (async done
         (->
           (go-promise
             (let [*events (atom [])
                   *navigation (atom 0)
                   *resolve (atom nil)
                   pending (js/Promise. (fn [resolve _] (reset! *resolve resolve)))
                   dispatch! #(swap! *events conj %)
                   old-match
                     {:data {:name :cv, :module :cv, :page :page, :controllers [{:start identity}]}}
                   current-view (fn [] [:div "Current page"])
                   current-match {:data {:name :home, :view current-view}}]
               (routes/navigate! *navigation dispatch! (constantly pending) old-match)
               (routes/navigate! *navigation dispatch! (constantly pending) current-match)
               (@*resolve {:view {:page (fn [] [:div "Old page"])}})
               (js/setTimeout
                 (fn []
                   (let [navigations (filter #(= :page/navigate (first %)) @*events)]
                     (is (= 2 (count navigations)) "Cold destination commits its shell immediately")
                     (is (nil? (get-in (second (first navigations)) [:data :controllers]))
                         "Cold module controllers cannot run before their events register")
                     (is (= current-match (second (last navigations))))
                     (is (not-any? #(= :loading/on (first %)) @*events))
                     (done)))
                 0)))
           (.catch (fn [error] (is false (str error)) (done))))))
(deftest hydrated-route-does-not-enable-page-spinner
  (async done
         (-> (go-promise (let [before @ssr/*snapshot
                               *events (atom [])
                               *loads (atom 0)]
                           (reset! ssr/*snapshot {:path "/blog", :kind :blog})
                           (try (routes/navigate!
                                  (atom 0)
                                  #(swap! *events conj %)
                                  (fn [_]
                                    (swap! *loads inc)
                                    (js/Promise.resolve {:view {:page (fn [] [:div])}}))
                                  {:path "/blog", :data {:name :blog, :module :blog, :page :page}})
                                (is (= 1 @*loads) "Hydration still initializes the module")
                                (is (not-any? #(= :loading/on (first %)) @*events))
                                (finally (reset! ssr/*snapshot before)))
                           (js/setTimeout done 0)))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest module-load-failure-clears-loading
  (async done
         (-> (go-promise
               (let [*events (atom [])]
                 (routes/navigate! (atom 0)
                                   #(swap! *events conj %)
                                   (fn [_] (js/Promise.reject (js/Error. "Test load failure")))
                                   {:data {:name :docs, :module :docs, :page :page}})
                 (js/setTimeout
                   (fn []
                     (is (some #(= :page/navigate (first %)) @*events)
                         "Destination precedes the failed code load")
                     (is (some #(= :diag/new (first %)) @*events))
                     (let [fallback (some #(when (= [:error-page] (second %)) (nth % 2)) @*events)
                           form (fallback)]
                       (is (= "This page could not be loaded" (:title (nth form 3))))
                       (is (fn? (last form))
                           "Page failures offer retry instead of a misleading 404"))
                     (done))
                   0)))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest cached-modules-initialize-once-and-return-native-promises
  (async done
         (-> (go-promise
               (let [id (keyword (str (random-uuid)))
                     *initializations (atom 0)
                     spec {:id id :view {:page (fn [] [:div])}, :init #(swap! *initializations inc)}
                     loadable (reify
                                lazy/ILoadable
                                  (ready? [_] true)
                                IDeref
                                  (-deref [_] spec))]
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
                         (.finally done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest module-load-forwards-hooks-and-initialization-arguments
  (async done
    (let [id (keyword (str (random-uuid)))
          *calls (atom [])
          spec {:id id :view {:view (fn [] [:div "Loaded"])}
                :init (fn [& args]
                        (go-promise (await! (support/settle!))
                                    (swap! *calls conj [:init args])))}
          loadable (reify lazy/ILoadable (ready? [_] true)
                    IDeref (-deref [_] spec))
          ;; A regular function keeps with-redefs from becoming an awaited
          ;; expression in cljs.test/async, so pending retains the Promise.
          load! (fn []
                  (with-redefs [loader/modules {id loadable}]
                    (loader/load! {:module id :args [:first {:second true}]
                                   :pre-fn (fn [& args] (swap! *calls conj [:pre args]))
                                   :post-fn (fn [loaded & args]
                                              (swap! *calls conj [:post loaded args]) loaded)})))
          pending (load!)]
      (-> pending
          (.then (fn [loaded]
                   (is (= spec loaded))
                   (is (= [[:pre [:first {:second true}]]
                           [:init [:first {:second true}]]
                           [:post spec [:first {:second true}]]] @*calls))))
          (.catch #(is false (str %)))
          (.finally #(do (swap! loader/*loads dissoc id)
                         (swap! loader/*code-loads dissoc id) (done)))))))

(deftest literal-search-completions
  (doseq [query ["C++" "[x]" "a.b" "(fn"]]
    (let [result (search/autocomplete-suggestion
                   query
                   {:highlights [{:snippet (str "Try <mark>" query "</mark> next")}],
                    :text_match 10})]
      (is (= (str query " next") (:text result)))))
  (is (nil? (search/autocomplete-suggestion "missing" {:highlights [{:snippet "Other text"}]})))
  (is (nil? (search/autocomplete-suggestion "query" {}))))
(deftest active-profile-merges-live-public-fields-with-private-details
  (async done
         (-> (go-promise
               (let [restore! (rf/make-restore-fn)
                     *rows (atom {:users [{:id "u", :name "Before", :karma 9}]})
                     transport (fake-scoped-transport! *rows)
                     _ (rf/dispatch-sync [:state [:active-user]
                                          {:id "u",
                                           :name "Private snapshot",
                                           :karma 1,
                                           :roles ["admins"],
                                           :comment-votes {:c 1}}])
                     {:keys [values unmount!]} (await! (mount-subscriptions!
                                                         {:user [:user/active-user]}))]
                 (-> (wait-for! #(= 9 (get-in (values) [:user :karma])))
                     (.then (fn [_]
                              (is (= ["admins"] (get-in (values) [:user :roles])))
                              (reset! *rows {:users [{:id "u", :name "After", :karma 10}]})
                              ((:change! transport) "site_users")
                              (wait-for! #(= "After" (get-in (values) [:user :name])))))
                     (.then (fn [_]
                              (is (= 10 (get-in (values) [:user :karma])))
                              (is (= {:c 1} (get-in (values) [:user :comment-votes])))))
                     (.catch #(is false (str %)))
                     (.finally (fn [] (unmount!) ((:close! transport)) (restore!) (done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest mounted-blog-subscriptions-load-update-and-release-their-readers
  (async
    done
    (->
      (go-promise
        (let [restore! (rf/make-restore-fn)
              post (fn [id]
                     {:id id,
                      :doc_id (str id),
                      :title (str "Post " id),
                      :text "Post body",
                      :tags "test",
                      :ts id})
              *rows (atom {:blog_posts (mapv post [1 24 28]),
                           :blog_comments
                             [{:id "root", :parent_post 24, :text "Root comment", :ts 1}
                              {:id "reply",
                               :parent_post 24,
                               :parent_comment "root",
                               :text "Nested reply",
                               :ts 2} {:id "other", :parent_post 28, :text "Other post", :ts 3}]})
              transport (fake-scoped-transport! *rows)
              queries {:ids [:blog/post-ids],
                       :first [:blog/ids-for-page 0 2],
                       :second [:blog/ids-for-page 1 2],
                       :post [:blog/post 24],
                       :count [:blog/count],
                       :prev [:blog/adjacent-post-id :prev 24],
                       :next [:blog/adjacent-post-id :next 24],
                       :roots [:comments/for-q-flat 24],
                       :replies [:comments/for-q-flat 24 "root"]}
              {:keys [values unmount!]} (await! (mount-subscriptions! queries))
              *second (atom nil)]
          (-> (wait-for! #(and (= "Post 24" (get-in (values) [:post :title]))
                               (= #{:reply} (set (keys (:replies (values)))))))
              (.then
                (fn [_]
                  (go-promise (let [v (values)]
                                (is (= [28 24 1] (:ids v)))
                                (is (= [28 24] (:first v)))
                                (is (= [1] (:second v)))
                                (is (= 3 (:count v)))
                                (is (= [28 1] [(:prev v) (:next v)]))
                                (is (= #{:root} (set (keys (:roots v)))))
                                (is (= "Root comment" (get-in v [:roots :root :text])))
                                (is (= "Nested reply" (get-in v [:replies :reply :text]))))
                              (let [reads (count @(:reads transport))]
                                (reset! *second (await! (mount-subscriptions! queries)))
                                (is (= (values) ((:values @*second))))
                                (is (= reads (count @(:reads transport)))
                                    "Another mounted owner shares the reader"))
                              (swap! *rows update :blog_posts conj (post 29))
                              (swap! *rows update
                                :blog_comments
                                conj
                                {:id "new-reply",
                                 :parent_post 24,
                                 :parent_comment "root",
                                 :text "Live reply",
                                 :ts 4})
                              ((:change! transport) "blog_posts")
                              ((:change! transport) "blog_comments")
                              (wait-for! #(and (= [29 28 24 1] (:ids (values)))
                                               (= #{:reply :new-reply}
                                                  (set (keys (:replies (values))))))))))
              (.then (fn [_]
                       (is (= [29 28] (:first (values))))
                       (unmount!)
                       (is (seq @scoped/*readers) "Second mounted owner keeps the readers alive")
                       (swap! *rows update :blog_posts #(filterv (fn [p] (not= 29 (:id p))) %))
                       ((:change! transport) "blog_posts")
                       (wait-for! #(= [28 24 1] (:ids ((:values @*second)))))))
              (.then (fn [_]
                       ((:unmount! @*second))
                       (reset! *second nil)
                       (wait-for! #(empty? @scoped/*readers))))
              (.then (fn [_]
                       (go-promise (is (empty? @scoped/*readers)
                                       "React unmount releases managed subscriptions")
                                   (is (seq (await! (state-at! [:store :scoped])))
                                       "Unmount retains event-populated content cache"))))
              (.catch #(is false (str %)))
              (.finally (fn []
                          (unmount!)
                          (when @*second ((:unmount! @*second)))
                          ((:close! transport))
                          (restore!)
                          (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest active-vote-remains-clickable-unless-write-is-pending
  (async done
         (-> (go-promise
               (let [pending (ratom/atom false)
                     element (.createElement js/document "div")
                     root (await! (support/create-root! element))]
                 (with-redefs [rf/subscribe (fn
                                              ([[event]]
                                               (if (= :blog/vote event) (ratom/atom :up) pending))
                                              ([_ _] pending))]
                   (try (await!
                          (support/render!
                            root
                            [blog-views/<vote-btn>
                             {:user "author", :active-user "voter", :path [1 "c"], :vote :up}]))
                        (is (false? (.-disabled (.querySelector element "button"))))
                        (reset! pending true)
                        (r/flush)
                        (is (true? (.-disabled (.querySelector element "button"))))
                        (finally (support/unmount! root))))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest blog-navigation-rejects-invalid-pages-and-page-sizes
  (async done
    (-> (go-promise
          (let [restore! (rf/make-restore-fn)]
            (try
              (rf/dispatch [:blog/nav-page "3"])
              (rf/dispatch [:blog/set-posts-per-page "5"])
              (await! (support/settle!))
              (is (= 2 (await! (state-at! [:state :blog :page]))))
              (is (= 5 (await! (state-at! [:options :blog :posts-per-page]))))
              (doseq [number [nil "" "invalid" "2oops" "0" "-1" -2 js/NaN 1.5]]
                (rf/dispatch [:blog/nav-page number])
                (await! (support/settle!))
                (is (= 2 (await! (state-at! [:state :blog :page])))
                    "An invalid event retains the last valid page"))
              (rf/dispatch [:blog/set-posts-per-page 0])
              (await! (support/settle!))
              (is (= 5 (await! (state-at! [:options :blog :posts-per-page]))))
              (is (seq (await! (support/subscription-value! [:validation/errors]))))
              (is (nil? (blog-model/page-ids [3 2 1] -1 2)))
              (is (nil? (blog-model/page-ids [3 2 1] 0 0)))
              (is (= [] (blog-model/page-ids [3 2 1] 9 2)))
              (finally (restore!)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(deftest blog-tags-handle-missing-values-whitespace-and-sequences
  (is (= [] (blog-model/tags nil)))
  (is (= ["clojure" "web"] (blog-model/tags "  clojure\tweb  clojure ")))
  (is (= ["web"] (blog-model/tags [nil "" " web " "web"]))))
(deftest blog-feed-keeps-post-subscriptions-reactive-after-initial-loading
  (async done
         (-> (go-promise
               (let [element (.createElement js/document "div")
                     root (await! (support/create-root! element))
                     *post (r/atom [{:id 42}])]
                 (.appendChild (.-body js/document) element)
                 (with-redefs [instrumentation/instrument (fn ([_ form] form) ([_ form _ _] form))
                               rf/subscribe (fn
                                              ([query]
                                               (case (first query)
                                                 :blog/count (r/atom 1)
                                                 :blog/posts-per-page (r/atom 3)
                                                 :blog/nav-page (r/atom 0)
                                                 :blog/posts-for-page *post))
                                              ([query _] (rf/subscribe query)))
                               blog-views/<blog-post>
                                 (fn [{:keys [post]}] [:p (or (:text post) "Loading")])
                               blog-views/<blog-nav> (fn [_] nil)]
                   (try (await! (support/render! root [blog-views/<blog-feed>]))
                        (is (= "Loading" (.-textContent element)))
                        (reset! *post [{:id 42, :text "Loaded asynchronously"}])
                        (r/flush)
                        (is (= "Loaded asynchronously" (.-textContent element)))
                        (reset! *post [{:id 42, :text "Live edit"}])
                        (r/flush)
                        (is (= "Live edit" (.-textContent element)))
                        (finally (support/unmount! root) (.remove element))))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest tag-view-mounts-before-content-and-updates-reactively
  (async done
         (-> (go-promise
               (let [element (.createElement js/document "div")
                     root (await! (support/create-root! element))
                     *posts (r/atom nil)]
                 (with-redefs [instrumentation/instrument (fn ([_ form] form) ([_ form _ _] form))
                               rf/subscribe (fn
                                              ([query]
                                               (case (first query)
                                                 :blog/state (r/atom "cljs")
                                                 :blog/posts-with-tag *posts))
                                              ([query _] (rf/subscribe query)))
                               blog-views/<blog-container> (fn [{:keys [section]}] section)
                               blog-views/<blog-post> (fn [{:keys [post]}] [:p (:text post)])
                               loading/<query-fallback> (fn [_] [:p "Pending"])]
                   (try (await! (support/render! root [blog-views/<blog-tag-view>]))
                        (is (= "Posts tagged cljsPending" (.-textContent element)))
                        (reset! *posts [{:id 42, :text "Filtered content"}])
                        (r/flush)
                        (is (= "Posts tagged cljsFiltered content" (.-textContent element)))
                        (reset! *posts [])
                        (r/flush)
                        (is (= "Posts tagged cljs" (.-textContent element))
                            "A completed empty tag is not a pending read")
                        (finally (support/unmount! root))))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest managed-query-failures-replace-pending-spinner
  (async
    done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                *failure (r/atom nil)]
            (with-redefs [rf/subscribe (fn ([_] *failure) ([_ _] *failure))]
              (try (await! (support/render! root
                                            [loading/<query-fallback>
                                             {:path-collection [:blog-posts], :scoped? true}]))
                   (is (some? (.querySelector element "[role=status]")))
                   (reset! *failure {:title "Content unavailable", :message "Please try again"})
                   (r/flush)
                   (is (nil? (.querySelector element "[role=status]")))
                   (is (.includes (.-textContent element) "Please try again"))
                   (is (some? (.querySelector element "button")) "Failure retains a retry control")
                   (finally (support/unmount! root))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(deftest header-routes-survive-json-roundtrip-on-direct-blog-load
  (async done
         (-> (go-promise
               (let [before (rf/make-restore-fn)
                     element (.createElement js/document "div")
                     root (await! (support/create-root! element))
                     menu {:work [["Services" "/services" "services"] ["Story" "/about" "about"]
                                  ["Hire me" "/hire" "hire"]],
                           :personal [["Blog" "/blog" "blog"]]}]
                 (try (rf/clear-subscription-cache!)
                      (rf/dispatch-sync [:set [:common/route]
                                         (reitit/match-by-path routes/router "/blog")])
                      (with-redefs [rfe/href
                                      (fn
                                        ([route] (:path (reitit/match-by-name routes/router route)))
                                        ([route _params _query]
                                         (:path (reitit/match-by-name routes/router route))))]
                        (await! (support/render! root [common/<header-nav> menu]))
                        (is (= ["/services" "/about" "/hire" "/blog"]
                               (mapv #(.getAttribute % "href")
                                 (array-seq (.querySelectorAll element "a"))))))
                      (finally (support/unmount! root) (rf/clear-subscription-cache!) (before)))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest fragment-links-retain-current-route-parameters
  (async done
         (-> (go-promise (let [before (rf/make-restore-fn)
                               *calls (atom [])]
                           (try (rf/clear-subscription-cache!)
                                (rf/dispatch-sync
                                  [:set [:common/route]
                                   (reitit/match-by-path routes/router "/blog/post/A-new-era-28")])
                                (with-redefs [rfe/href (fn [route params _query]
                                                         (swap! *calls conj [route params])
                                                         "/blog/post/A-new-era-28")]
                                  (is (= "/blog/post/A-new-era-28#main"
                                         (await! (subscription-value! [:href "#main"]))))
                                  (is (= [[:blog-post {:permalink "A-new-era-28"}]] @*calls)))
                                (finally (rf/clear-subscription-cache!) (before)))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest shim-keeps-registration-call-site-in-debug-builds
  (shim/reg-event-db :test/instrumented-shim (fn [db _] db))
  (try (let [source (meta (registrar/get-handler :event :test/instrumented-shim))]
         (is (re-find #"workflow_unit_test.cljs" (:file source)))
         (is (pos? (:line source))))
       (finally (rf/clear-event :test/instrumented-shim))))
(deftest bootstrap-fallback-follows-state-and-retries-without-owning-page-dom
  (async done
         (-> (go-promise
               (let [before (rf/make-restore-fn)
                     element (.createElement js/document "div")
                     retained (.createElement js/document "article")
                     root (await! (support/create-root! element))
                     *retries (atom 0)
                     retry! #(do (swap! *retries inc)
                                 (rf/dispatch-sync [:state [:page-init] {:status :loading}]))]
                 (.appendChild (.-body js/document) retained)
                 (.appendChild (.-body js/document) element)
                 (try (do (rf/dispatch-sync [:state [:page-init] {:status :failed}])
                          (await! (support/render! root [init-view/<fallback> retry!])))
                      (is (some? (.querySelector element "[role=alert]")))
                      (.click (.querySelector element "button"))
                      (r/flush)
                      (is (= 1 @*retries))
                      (is (nil? (.querySelector element "[role=alert]")))
                      (is (.-isConnected retained) "Retry preserves the separate page root")
                      (finally (support/unmount! root)
                               (.remove element)
                               (.remove retained)
                               (rf/clear-subscription-cache!)
                               (before)))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest background-initialization-does-not-dispatch-loading-events
  (let [handler (tolgraven.events/get-http-fn :get)
        effects (handler {:db {}} [:http/get {:uri "/api/supabase/settings", :background? true}])]
    (is (not (contains? effects :dispatch)))
    (is (nil? (get-in effects [:http-xhrio :on-success 2])))
    (is (not (contains? (:http-xhrio effects) :background?)))))
(deftest subscription-preload-releases-only-its-own-owner
  (async
    done
    (->
      (go-promise
        (let [restore! (rf/make-restore-fn)
              _ (rf/reg-sub :test/preload-ready :<- [:state [:preload-ready]] (fn [ready _] ready))
              _ (rf/dispatch-sync [:state [:preload-ready] false])
              {:keys [values unmount!]} (await! (mount-subscriptions! {:ready
                                                                         [:test/preload-ready]}))
              load! (:load! (get @data/*sources :subscription))]
          (-> (load! {:query [:test/preload-ready], :timeout-ms 2000})
              (.then (fn [ready]
                       (is (true? ready))
                       (is (true? (:ready (values)))
                           "Mounted owner remains reactive after preload releases")
                       (is (some #{[:test/preload-ready]} (tooling/live-query-vs)))
                       (unmount!)
                       (wait-for! #(not (some #{[:test/preload-ready]} (tooling/live-query-vs))))))
              (.then (fn [_] (is (not (some #{[:test/preload-ready]} (tooling/live-query-vs))))))
              (.catch #(is false (str %)))
              (.finally (fn [] (unmount!) (restore!) (done))))
          (rf/dispatch-sync [:state [:preload-ready] true])))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest codox-links-are-rewritten-before-rendering
  (is (= "<a href=\"/docs/codox/tolgraven.core#init\">Init</a>"
         (docs-view/page-links "<a href=\"tolgraven.core.html#init\">Init</a>")))
  (doseq [url ["https://example.com/page.html" "#heading" "/blog" "mailto:test@example.com"]]
    (let [html (str "<a href=\"" url "\">Link</a>")] (is (= html (docs-view/page-links html))))))
(deftest markdown-code-uses-reagent-props-without-js-conversion
  (let [html (server/render-to-static-markup [code/<markdown-code-component> {:children "inline"}])]
    (is (re-matches #"<code(?: data-dev-component=\"[^\"]*\")?>inline</code>" html))))
(deftest debug-and-theme-events-preserve-app-db
  (async done
         (-> (go-promise (let [before (rf/make-restore-fn)]
                           (try (rf/dispatch-sync [:set [:sentinel] :retained])
                                (rf/dispatch-sync [:debug [:layers] true])
                                (rf/dispatch-sync [:theme/dark-mode true])
                                (rf/dispatch-sync [:theme/colorscheme "test"])
                                (is (= :retained (await! (state-at! [:sentinel]))))
                                (is (true? (await! (state-at! [:state :debug :layers]))))
                                (is (= {:dark-mode true, :colorscheme "test"}
                                       (select-keys (await! (state-at! [:options :theme]))
                                                    [:dark-mode :colorscheme])))
                                (finally (before)))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest markdown-code-renders-through-the-react-adapter
  (async done
         (-> (go-promise (let [element (.createElement js/document "div")
                               root (await! (support/create-root! element))]
                           (try (await! (support/render!
                                          root
                                          [code/<parse-markdown-components>
                                           "Inline `hello`\n\n```clojure\n(+ 1 2)\n```\n"]))
                                (is (.includes (.-textContent element) "hello"))
                                (is (.includes (.-textContent element) "(+ 1 2)"))
                                (is (some? (.querySelector element "pre code")))
                                (finally (support/unmount! root)))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(defn- event-effects
  [id coeffects event]
  ;; Exercise the registered handler without running navigation/timer effects.
  (let [handler (-> (registrar/get-handler :event id)
                    last
                    :before)]
    (:effects (handler {:coeffects (assoc coeffects :event event)}))))
(deftest first-navigation-keeps-ssr-position-and-later-navigation-still-scrolls
  (let [match {:path "/blog/post/A-new-era-28", :data {:name :blog-post, :view identity}}
        effects (event-effects :common/navigate
                               {:db {}, :scroll-position 0, :id {:id {:navigations 0}}}
                               [:common/navigate match])
        scroll-event (some #(when (= :scroll/on-navigate (first %)) %) (:dispatch-n effects))]
    (is (= [:scroll/on-navigate (:path match) 0 nil] scroll-event)
        "The injected ID counter marks the initial route as the first navigation")
    (let [initial (event-effects :scroll/on-navigate {:db {}} scroll-event)
          subsequent
            (event-effects :scroll/on-navigate {:db {}} [:scroll/on-navigate (:path match) 1])
          returning (event-effects :scroll/on-navigate
                                   {:db {:state {:browser-nav {:got-nav true},
                                                 :scroll-position {(:path match) 250}}}}
                                   [:scroll/on-navigate (:path match) 2])]
      (is (some #{[:page/ready nil nil]} (:dispatch-n initial))
          "Hydration does not scroll an already visible page")
      (is (some #{[:page/ready "main" nil]} (:dispatch-n subsequent))
          "Ordinary SPA navigation keeps its scroll-to-content behavior")
      (is (some #{[:page/ready 250 nil]} (:dispatch-n returning))
          "Browser back navigation still restores a saved position"))))
(deftest landing-navigation-preloads-the-actual-page-content
  (let [home (first (routes/landing-dependencies {:data {:name :home}}))
        about (first (routes/landing-dependencies {:data {:name :about}}))]
    (is (= :strapi (:source home)))
    (is (every? (set (:keys home)) [:header :footer :intro :services :gallery]))
    (is (contains? (set (:keys about)) :story))
    (is (not (contains? (set (:keys about)) :intro)))))
(deftest page-transition-skips-hydration-and-query-only-changes
  (let [a {:path "/blog"}
        b {:path "/"}]
    (is (false? (second (:page/transition
                          (event-effects :page/navigate {:db {}} [:page/navigate a])))))
    (is (false? (second (:page/transition (event-effects :page/navigate
                                                         {:db {:common/route a}}
                                                         [:page/navigate
                                                          (assoc a
                                                            :query-params {:userBox "true"})])))))
    (is (true? (second (:page/transition (event-effects :page/navigate
                                                        {:db {:common/route a}}
                                                        [:page/navigate b])))))))
(deftest obsolete-transition-completion-cannot-scroll-a-new-page
  (let [*completed (atom 0)
        *flushed (atom 0)]
    (with-redefs [r/after-render (fn [f] (f))
                  r/flush #(swap! *flushed inc)]
      (page-transition/ready! 999999
                              {:current? (constantly false), :resolve! #(swap! *completed inc)}))
    (is (= 1 @*completed) "Release the skipped native transition")
    (is (zero? @*flushed) "Do not commit or reposition an obsolete page")))
(deftest store-startup-is-shared-by-return-preload-and-hydration
  (doseq [status [:loading :ready]]
    (is (nil? (:dispatch
                (event-effects :store/init {:db {:state {:supabase-init status}}} [:store/init])))))
  (is (= [:supabase/fetch-settings]
         (:dispatch
           (event-effects :store/init {:db {:state {:supabase-init :failed}}} [:store/init])))))
(deftest native-page-transition-commits-destination-before-content-is-ready
  (async
    done
    (->
      (go-promise
        (let [before (rf/make-restore-fn)
              element (.createElement js/document "div")
              root (await! (support/create-root! element))
              *content-ready? (r/atom false)
              old {:path "/transition-old", :data {:view (fn [] [:div#old-transition-page "Old"])}}
              next {:path "/transition-new", :data {:view (fn [] [:div#new-transition-page
                                                                (if @*content-ready? "New" "Loading destination")])}}
              fixture (fn []
                        (let [route @(rf/subscribe [:common/route])]
                          (page-transition/use-ready! @(rf/subscribe [:get :page/commit]))
                          [:main#main {:style {:min-height "100vh"}} [:h1 "Shared heading"]
                           [(get-in route [:data :view])]]))]
          (.appendChild (.-body js/document) element)
          (rf/dispatch-sync [:set [:common/route] old])
          (await! (support/render! root [:f> fixture]))
          (-> (page-transition/navigate! next true)
              (.then (fn [_]
                       (is (nil? (.querySelector element "#old-transition-page")))
                       (is (some? (.querySelector element "#new-transition-page")))
                       (is (= 1 (.-length (.querySelectorAll element "main"))))
                       (is (= "Shared heading" (.-textContent (.querySelector element "h1"))))
                       (is (= "Loading destination" (.-textContent (.querySelector element "#new-transition-page")))
                           "The transition completes while destination content is still pending")
                       (reset! *content-ready? true)
                       (wait-for! #(= "New" (some-> (.querySelector element "#new-transition-page") .-textContent)))))
              (.catch #(is false (str %)))
              (.finally (fn []
                          (support/unmount! root)
                          (.remove element)
                          (rf/clear-subscription-cache!)
                          (before)
                          (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest native-frame-rate-hint-targets-only-the-current-page-crossfade
  (async done
    (-> (go-promise
          (let [original-start (.-startViewTransition js/document)
                original-animations (.-getAnimations js/document)
                original-transition @page-transition/*transition
                original-generation @page-transition/*generation
                original-destination @page-transition/*destination
                outgoing #js {:animationName "page-opacity-out", :frameRate "auto"}
                incoming #js {:animationName "page-opacity-in-a", :frameRate "auto"}
                unrelated #js {:animationName "blinking", :frameRate "auto"}
                transition #js {:ready (js/Promise.resolve nil)
                                :finished (js/Promise.resolve nil)
                                :skipTransition (fn [])}]
            (try
              (reset! page-transition/*transition nil)
              (set! (.-startViewTransition js/document) (fn [_] transition))
              (set! (.-getAnimations js/document) (fn [] #js [outgoing incoming unrelated]))
              (page-transition/navigate! {:path "/frame-rate"} true)
              (await! (.-ready transition))
              (is (= "highest" (.-frameRate outgoing)))
              (is (= "highest" (.-frameRate incoming)))
              (is (= "auto" (.-frameRate unrelated))
                  "Other page animations retain their browser-selected rate")
              (finally
                (set! (.-startViewTransition js/document) original-start)
                (set! (.-getAnimations js/document) original-animations)
                (reset! page-transition/*transition original-transition)
                (reset! page-transition/*generation original-generation)
                (reset! page-transition/*destination original-destination)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest route-change-with-a-query-change-still-positions-the-new-page
  (let [old {:path "/blog", :query-params {:userBox "false"}, :data {:view identity}}
        next {:path "/", :data {:view identity}}
        effects (event-effects
                  :common/navigate
                  {:db {:common/route old}, :scroll-position 700, :id {:id {:navigations 1}}}
                  [:common/navigate next])]
    (is (some #{[:scroll/on-navigate "/" 1 nil]} (:dispatch-n effects))))
  (let [effects (event-effects :scroll/on-navigate
                               {:db {:state {:browser-nav {:got-nav true}}}}
                               [:scroll/on-navigate "/" 2])]
    (is (false? (get-in effects [:db :state :browser-nav :got-nav]))
        "A later link click must not inherit this navigation's Back flag")))

(deftest loaded-code-replaces-the-destination-shell-without-a-second-transition
  (let [shell {:path "/cv", :data {:view (fn [] [:div "Loading CV"])}}
        page (assoc-in shell [:data :view] (fn [] [:div "CV"]))
        completion {:target "main" :completion {:resolve! identity}}
        effects (event-effects :common/navigate
                               {:db {:common/route shell :page/commit completion}
                                :scroll-position 200 :id {:id {:navigations 2}}}
                               [:common/navigate page {:replace-shell? true}])]
    (is (= page (dissoc (get-in effects [:db :common/route]) :controllers)))
    (is (= completion (get-in effects [:db :page/commit]))
        "Keep the first commit's completion if code arrives before React commits")
    (is (= [[:document/set-title! (get-in effects [:db :common/route])]] (:dispatch-n effects))
        "Code arrival neither repositions the page nor starts another transition")))

(deftest queued-native-shell-cannot-overwrite-an-already-committed-module
  (let [shell {:path "/cv" :data {:view (fn [] [:div "Loading CV"])}}
        page (assoc-in shell [:data :view] (fn [] [:div "CV"]))
        coeffects {:db {:common/route shell} :scroll-position 200
                   :id {:id {:navigations 2}}}
        replacement (event-effects :common/navigate coeffects
                                   [:common/navigate page {:replace-shell? true}])
        completion {:transition-id 7 :resolve! identity}
        pending {:generation 7 :match (:page/replace-destination replacement)}
        ;; Both events were queued before the replacement effect ran. The
        ;; native callback captured shell; the handler must now resolve page.
        stale (event-effects :common/navigate
                             (assoc coeffects :db (:db replacement) :page/destination pending)
                             [:common/navigate shell completion])]
    (is (= page (dissoc (get-in (:db replacement) [:common/route]) :controllers)))
    (is (not (contains? stale :db)) "The stale shell must not replace the real page")
    (is (= [:page/ready nil completion] (:dispatch stale))
        "The original transition still receives completion")
    (is (= shell (page-transition/latest-match shell {:transition-id 6} pending))
        "An old transition cannot borrow a newer generation")
    (is (= shell (page-transition/latest-match shell completion
                                               (assoc-in pending [:match :path] "/blog")))
        "An unrelated address is never substituted")))

(deftest code-ready-navigation-does-not-await-managed-data
  (async done
         (-> (go-promise
               (let [id (keyword (str (random-uuid)))
                     spec {:id id :view {:page (fn [] [:div "Destination"])}}
                     loadable (reify
                                lazy/ILoadable
                                  (ready? [_] true)
                                IDeref
                                  (-deref [_] spec))
                     original loader/load!
                     *finish (atom nil)
                     pending (js/Promise. (fn [resolve _] (reset! *finish resolve)))
                     *events (atom [])]
                 (set! loader/load! (constantly pending))
                 (with-redefs [loader/modules {id loadable}]
                   (routes/navigate! (atom 0)
                                     #(swap! *events conj %)
                                     loader/load-code!
                                     {:path "/pending-data", :data {:module id, :page :page}})
                   (is (= :page/navigate (ffirst @*events)))
                   (is (= (get-in spec [:view :page]) (get-in @*events [0 1 :data :view])))
                   (-> (loader/load-code! {:module id, :view :page})
                       (.then (fn [actual]
                                (is (= spec actual) "Code resolves while data is still pending")
                                (@*finish spec)))
                       (.catch #(is false (str %)))
                       (.finally (fn [] (set! loader/load! original) (done)))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest page-transition-does-not-wait-for-image-downloads
  (async done
         (-> (go-promise
               (let [main (.createElement js/document "main")
                     image (.createElement js/document "img")
                     *completed (atom false)]
                 (set! (.-id main) "main")
                 (set! (.-decode image) (fn [] (js/Promise. (fn [_ _]))))
                 (set! (.-getBoundingClientRect image) (fn [] #js {:top 0, :bottom 100}))
                 (.appendChild main image)
                 (.appendChild (.-body js/document) main)
                 (with-redefs [r/after-render (fn [_] (throw (js/Error. "Transition must not wait for an animation frame")))]
                   (page-transition/ready! nil
                                           {:current? (constantly true),
                                            :resolve! #(reset! *completed true)}))
                 (-> (js/Promise.resolve)
                     (.then (fn [_]
                              (is @*completed
                                  "An indefinitely pending image must not freeze navigation")))
                     (.finally (fn [] (.remove main) (done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest cold-hydration-never-installs-a-loading-shell
  (async done
         (-> (go-promise
               (let [context @restore/*context
                     snapshot @ssr/*snapshot
                     *events (atom [])
                     *finish (atom nil)
                     pending (js/Promise. (fn [resolve _] (reset! *finish resolve)))
                     view (fn [] [:article "Server article"])]
                 (reset! restore/*context {:hydrate? true})
                 (reset! ssr/*snapshot {:path "/blog/post/cold-28"})
                 (routes/navigate! (atom 0)
                                   #(swap! *events conj %)
                                   (constantly pending)
                                   {:path "/blog/post/cold-28", :data {:module :blog, :page :post}})
                 (is (empty? @*events) "Preserve existing server DOM while code loads")
                 (@*finish {:view {:post view}})
                 (-> pending
                     (.then (fn [_]
                              (is (= 1 (count @*events)))
                              (is (= view (get-in @*events [0 1 :data :view])))))
                     (.finally (fn []
                                 (reset! restore/*context context)
                                 (reset! ssr/*snapshot snapshot)
                                 (done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest history-navigation-declares-restoration-before-page-commit
  (let [before @restore/*context
        *events (atom [])]
    (try (with-redefs [shim/dispatch #(swap! *events conj %)]
           (page-transition/navigate! {:path (restore/page-key)} true true))
         (is (restore/skip-enter?))
         (is (not (restore/initial-enter?)))
         (restore/navigate! "/another-page")
         (is (not (restore/skip-enter?)) "Ordinary navigation enables motion again")
         (finally (reset! restore/*context before)))))
(deftest client-restored-content-requests-layout-aware-scroll-restoration
  (is (= {:scroll/restore-position 499}
         (event-effects :scroll/restore-history
                        {:db {:common/route {:path "/blog/post/27"},
                              :state {:scroll-position {"/blog/post/27" 499}}}}
                        [:scroll/restore-history]))))
(deftest related-blog-pages-keep-the-shared-layout-out-of-page-transitions
  (let [a (reitit/match-by-path routes/router "/blog/post/28")
        b (reitit/match-by-path routes/router "/blog/post/27")]
    (is (false? (second (:page/transition (event-effects :page/navigate
                                                         {:db {:common/route a}}
                                                         [:page/navigate b])))))))
(deftest error-boundary-route-reset-does-not-remount-a-healthy-layout
  (async done
         (-> (go-promise
               (let [element (.createElement js/document "div")
                     root (await! (support/create-root! element))
                     view (fn [text] [:section [:h1 "Shared heading"] [:article text]])]
                 (try (await! (support/render! root [ui/<safe> :page [view "First"] "/first"]))
                      (let [heading (.querySelector element "h1")]
                        (await! (support/render! root [ui/<safe> :page [view "Second"] "/second"]))
                        (is (identical? heading (.querySelector element "h1")))
                        (is (= "Second" (.-textContent (.querySelector element "article")))))
                      (finally (support/unmount! root)))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest external-navigation-saves-the-current-path-not-a-captured-route-name
  (let [effects (event-effects :scroll/save-history
                               {:db {:common/route {:path "/blog/post/27"}}, :scroll-position 499}
                               [:scroll/save-history])]
    (is (= 499 (get-in effects [:db :state :scroll-position "/blog/post/27"])))))
(deftest search-is-closed-unless-explicitly-opened
  (async done
         (-> (go-promise (let [before (rf/make-restore-fn)]
                           (try (doseq [[state expected] [[nil false] [false false] [true true]]]
                                  (rf/dispatch-sync [:search/state [:open?] state])
                                  (is (= expected (await! (subscription-value! [:search/open?])))))
                                (finally (before)))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest first-document-route-restores-scroll-even-with-persisted-navigation-counters
  (let [effects (event-effects :common/navigate
                               {:db {:state {:scroll-position {"/blog/post/17" 499}}},
                                :scroll-position 0,
                                :id {:id {:navigations 83}}}
                               [:common/navigate {:path "/blog/post/17", :data {}} nil])]
    (is (some #{[:scroll/on-navigate "/blog/post/17" 0 nil]} (:dispatch-n effects)))
    (is (= [:document/set-title! (get-in effects [:db :common/route])]
           (first (:dispatch-n effects))))))

(deftest loaded-component-title-refreshes-current-document-title
  (let [route {:path "/blog/post/17" :data {:name :blog-post}}
        effects (event-effects :common/set-title {:db {:common/route route}}
                               [:common/set-title "Loaded post"])]
    (is (= "Loaded post" (get-in effects [:db :state :document :title])))
    (is (= [:document/set-title! route] (:dispatch effects)))))

(deftest document-title-accepts-coerced-page-numbers
  (let [effects (event-effects :document/set-title!
                 {:db {:content {:document {:title "Site"}}}}
                 [:document/set-title! {:parameters {:path {:nr 2}}
                                        :data {:name :blog-page}}])]
    (is (= "2 - Blog-page  - Site" (:document/set-title effects)))))
(deftest cold-history-return-does-not-consume-restoration-on-an-interim-shell
  (let [context @restore/*context
        *events (atom [])
        pending (js/Promise. (fn [_ _]))]
    (try (restore/begin! {:back? true})
         (routes/navigate! (atom 0)
                           #(swap! *events conj %)
                           (constantly pending)
                           {:path (restore/page-key), :data {:module :blog, :page :post}})
         (is (empty? @*events))
         (finally (reset! restore/*context context)))))
(deftest restoration-scroll-does-not-toggle-the-header
  (let [coeffects {:db {:state {:hidden {:header false :footer false}}}
                   :css-var {}
                   :scroll/restoring? true}
        effects (event-effects :scroll/direction coeffects [:scroll/direction :down 485 2000 false false])]
    (is (not-any? #(= :hide-header-footer (first %)) (:dispatch-n effects)))
    (is (some #{[:scroll/past-top true]} (:dispatch-n effects)))))

(deftest anchor-navigation-controls-scroll-until-settled-and-cancels-on-input
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                original-position (.-scrollY js/window)
                original-mode (.-scrollRestoration js/history)]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [:div {:style {:height "1600px"}} [:div#anchor-fixture "Target"]]))
              (page-transition/ready! "anchor-fixture" {})
              ;; Capture before assertions add rows to this scrolling runner DOM.
              (let [position (.-scrollY js/window)]
                (is (some? @page-transition/*restore-cleanup)
                    "Anchor positioning also guards the controlled scroll")
                (is (= position @page-transition/*restored-position)))
              (.dispatchEvent js/window (js/Event. "wheel"))
              (is (nil? @page-transition/*restore-cleanup))
              (is (nil? @page-transition/*restored-position))
              (is (empty? @restore/*layout-listeners))
              (finally
                (when-let [cleanup! @page-transition/*restore-cleanup] (cleanup!))
                (support/unmount! root) (.remove element)
                (set! (.-scrollRestoration js/history) original-mode)
                (.scrollTo js/window #js {:top original-position :behavior "instant"})))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest native-height-release-only-applies-to-the-current-navigation
  (let [db {:page/commit {:target "main"
                         :completion {:transition-id 9 :outgoing-height 2400.5}}}]
    (is (= db (:db (event-effects :page/transition-finished {:db db} [:page/transition-finished 8]))))
    (is (nil? (get-in (event-effects :page/transition-finished {:db db} [:page/transition-finished 9])
                     [:db :page/commit :completion :outgoing-height])))))

(deftest history-scroll-waits-for-layout-animation-completion
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                original-position (.-scrollY js/window)
                original-mode (.-scrollRestoration js/history)]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [:div#height-animation {:style {:height "600px"}}]))
              (let [animation (.animate (.querySelector element "#height-animation")
                                        #js [#js {:height "200px"} #js {:height "600px"}]
                                        #js {:duration 250})]
                (page-transition/restore-position! 0)
                (await! (support/settle!))
                (is (some? @page-transition/*restore-cleanup)
                    "Reachable scroll cannot finish during an active layout animation")
                (await! (.-finished animation))
                (await! (wait-for! #(nil? @page-transition/*restore-cleanup))))
              (is (empty? @restore/*layout-listeners))
              (finally
                (when-let [cleanup! @page-transition/*restore-cleanup] (cleanup!))
                (support/unmount! root) (.remove element)
                (set! (.-scrollRestoration js/history) original-mode)
                (.scrollTo js/window #js {:top original-position :behavior "instant"})))))
        (.catch #(is false (str %)))
        (.finally done))))

(defpage <restoration-data-page>
  {:depends [{:source :restoration-fixture}]}
  []
  [:section#restored-content {:style {:height "1800px"}} "Ready page"])

(deftest history-scroll-waits-for-mounted-data-then-measured-layout
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                original-position (.-scrollY js/window)
                original-mode (.-scrollRestoration js/history)
                original-source (get @data/*sources :restoration-fixture)
                *resolve (atom nil)]
            (.appendChild (.-body js/document) element)
            (data/register-source! :restoration-fixture
              {:load! (fn [_] (js/Promise. (fn [resolve _] (reset! *resolve resolve))))})
            (try
              (await! (support/render! root
                        [:r> (react/context-provider restore/readiness-context) #js {:value true}
                         [<restoration-data-page>]]))
              (await! (wait-for! #(some? @*resolve)))
              (is (not (restore/layout-ready?)))
              (page-transition/restore-position! 0)
              (await! (support/settle!))
              (is (some? @page-transition/*restore-cleanup)
                  "A reachable offset cannot complete while page data is still pending")
              (let [page-root (.querySelector element ".page-root")]
                (@*resolve {})
                (await! (wait-for! #(some? (.querySelector element "#restored-content"))))
                (is (identical? page-root (.querySelector element ".page-root"))
                    "The page boundary root survives loading-to-content replacement"))
              (await! (wait-for! #(nil? @page-transition/*restore-cleanup)))
              (is (restore/layout-ready?))
              (is (empty? @restore/*layout-listeners) "Completion releases readiness listeners")
              (is (= 0 (.-scrollY js/window)))
              (finally
                (when-let [cleanup! @page-transition/*restore-cleanup] (cleanup!))
                (support/unmount! root)
                (.remove element)
                (data/invalidate! #(= :restoration-fixture (:source %)))
                (if original-source
                  (data/register-source! :restoration-fixture original-source)
                  (swap! data/*sources dissoc :restoration-fixture))
                (set! (.-scrollRestoration js/history) original-mode)
                (.scrollTo js/window #js {:top original-position :behavior "instant"})))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest history-scroll-follows-layout-growth-and-releases-its-observer
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                original-position (.-scrollY js/window)
                original-mode (.-scrollRestoration js/history)]
            (.appendChild (.-body js/document) element)
            (try
              (let [target (+ 200 (- (.-scrollHeight (.-documentElement js/document))
                                     (.-innerHeight js/window)))]
                (page-transition/restore-position! target)
                (is (some? @page-transition/*restore-cleanup)
                    "A short initial layout retains the target")
                (await! (support/render! root [:div {:style {:height "1500px"}}]))
                (await! (support/wait-for! #(<= (js/Math.abs (- target (.-scrollY js/window))) 1)))
                (await! (support/wait-for! #(nil? @page-transition/*restore-cleanup)))
                (is (nil? @page-transition/*restore-cleanup)
                    "Stable layout disposes the observer after restoring the position")
                (page-transition/restore-position! (+ target 10000))
                (.dispatchEvent js/window (js/Event. "wheel"))
                (is (nil? @page-transition/*restore-cleanup)
                    "User input cancels a pending restoration"))
              (finally
                (when-let [cleanup! @page-transition/*restore-cleanup] (cleanup!))
                (support/unmount! root)
                (.remove element)
                (set! (.-scrollRestoration js/history) original-mode)
                (.scrollTo js/window #js {:top original-position :behavior "instant"})))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(defpage <crossfade-fixture> [spec]
  [:p {:id (:id spec)
       :style {:height (or (:height spec) "40px")}}
   (:text spec)])

(m/defc <crossfade-host> [incoming outgoing current previous animate? id]
  [:main#main
   (page-view/use-page-crossfade incoming outgoing current previous animate? id)])

(deftest page-container-merges-caller-and-navigation-root-props
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                *caller (atom nil) *navigation (atom nil)]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root
                        [:r> (react/context-provider component/page-root-context)
                         #js {:value {:class "navigation"
                                      :style {:opacity 0.5}
                                      :inert true
                                      :ref #(reset! *navigation %)}}
                         [<crossfade-fixture>
                          {:text "Content"
                           :props {:class "caller"
                                   :style {:color "red"}
                                   :ref #(reset! *caller %)}}]]))
              (let [page-root (.querySelector element ".page-root")]
                (is (and (identical? page-root @*caller) (identical? page-root @*navigation)))
                (is (.contains (.-classList page-root) "caller"))
                (is (.contains (.-classList page-root) "navigation"))
                (is (= "red" (.. page-root -style -color)))
                (is (= "0.5" (.. page-root -style -opacity)))
                (is (.hasAttribute page-root "inert"))
                (is (nil? (.querySelector element "p.caller"))
                    "Caller root props belong to the boundary, not the page's inner body"))
              (finally (support/unmount! root) (.remove element)))
            (is (nil? @*caller))
            (is (nil? @*navigation))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest fallback-transition-commits-pending-content-and-releases-old-page
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                current {:path "/new"} previous {:path "/old"}
                form (fn [id] [<crossfade-host> [<crossfade-fixture> {:id "destination" :text "Loading destination"}]
                               [<crossfade-fixture> {:id "outgoing" :text "Old page"}] current previous true id])]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root (form 1)))
              (is (some? (.querySelector element "#destination"))
                  "Destination mounts immediately while content is pending")
              (is (some? (.querySelector element "#outgoing")))
              (await! (wait-for! #(nil? (.querySelector element "#outgoing"))))
              (is (= "Loading destination" (.-textContent (.querySelector element "#destination"))))
              (await! (support/render! root (form 2)))
              (is (some? (.querySelector element "#outgoing"))
                  "Repeating the same route pair starts a fresh transition")
              (await! (wait-for! #(nil? (.querySelector element "#outgoing"))))
              (finally (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest fallback-css-crossfades-incoming-and-outgoing-together
  (async done
    (-> (go-promise
          (let [host (.createElement js/document "div")
                shadow (.attachShadow host #js {:mode "open"})
                stylesheet (.createElement js/document "link")
                native-stylesheet (.createElement js/document "link")
                element (.createElement js/document "div")
                *root (atom nil) *frames (atom [])
                *requested-layers (atom [])
                original-frame-rate (js/Object.getOwnPropertyDescriptor js/Animation.prototype "frameRate")]

            (.appendChild (.-body js/document) host)
            (.appendChild shadow element)
            (try
              ;; Expose the optional native boundary without changing CSS timing.
              (js/Object.defineProperty js/Animation.prototype "frameRate"
                #js {:configurable true
                     :get (fn [] "auto")
                     :set (fn [rate]
                            (this-as animation
                              (swap! *requested-layers conj
                                     {:rate rate,
                                      :class (.. animation -effect -target -className)})))})
              ;; Production CSS in an isolated fixture, not duplicated test rules.
              (await! (js/Promise.
                        (fn [resolve reject]
                          (set! (.-rel stylesheet) "stylesheet")
                          (set! (.-href stylesheet) "/css/tolgraven/main.min.css")
                          (set! (.-onload stylesheet) #(resolve nil))
                          (set! (.-onerror stylesheet) #(reject (js/Error. "Fixture CSS unavailable")))
                          (.appendChild shadow stylesheet))))
              ;; Native pseudo-elements live at the document root. Load the same
              ;; sheet there so both paths inherit the production timing variables.
              (await! (js/Promise.
                        (fn [resolve reject]
                          (set! (.-rel native-stylesheet) "stylesheet")
                          (set! (.-href native-stylesheet) (.-href stylesheet))
                          (set! (.-onload native-stylesheet) #(resolve nil))
                          (set! (.-onerror native-stylesheet) #(reject (js/Error. "Native CSS unavailable")))
                          (.appendChild (.-head js/document) native-stylesheet))))
              (reset! *root (await! (support/create-root! element)))
              (await! (support/render! @*root
                         [:div
                          [<crossfade-host> [<crossfade-fixture> {:id "destination" :text "Incoming" :height "160px"}]
                           [<crossfade-fixture> {:id "outgoing" :text "Outgoing" :height "640px"}]
                           {:path "/new"} {:path "/old"} true 1]
                          [:footer#footer-sticky.footer-sticky "Persistent footer"]]))
              (await! (wait-for!
                        (fn []
                          (let [incoming (.querySelector element ".swap-in")
                                outgoing (.querySelector element ".swapped")
                                opacity #(js/parseFloat (.-opacity (js/getComputedStyle %)))
                                frame {:incoming-top (.-top (.getBoundingClientRect incoming))
                                       :outgoing-top (when outgoing (.-top (.getBoundingClientRect outgoing)))
                                       :main-height (.-height (.getBoundingClientRect (.querySelector element "#main")))
                                       :incoming (opacity incoming)
                                       :footer (opacity (.querySelector element "#footer-sticky"))
                                       :outgoing (if outgoing (opacity outgoing) 0)}]
                            (swap! *frames conj frame)
                            (and (nil? outgoing) (>= (:incoming frame) 0.99))))))
              (is (some #(and (< 0.01 (:outgoing %) 0.99)
                              (< 0.01 (:incoming %) 0.99)) @*frames)
                  "Incoming and outgoing pages fade simultaneously")
              (is (every? #(<= (js/Math.abs (- 1 (+ (:incoming %) (:outgoing %)))) 0.05)
                          @*frames)
                  "Matching timing keeps the opacities complementary throughout the crossfade")
              (is (every? #(= 1 (:footer %)) @*frames)
                  "The fallback keeps the sticky footer fully opaque")
              (is (nil? (.querySelector element ".swapper")) "No outer swapper DOM wrapper")
              (is (= "DIV" (.-tagName (.querySelector element ".swap-in")))
                  "The page boundary owns the transition root")
              (is (every? #(or (nil? (:outgoing-top %))
                              (= (:incoming-top %) (:outgoing-top %))) @*frames)
                  "Unequal-height roots stay aligned throughout the crossfade")
              (is (every? #(or (nil? (:outgoing-top %)) (>= (:main-height %) 640)) @*frames)
                  "The outgoing root continues to contribute height until its fade ends")
              (is (= 2 (count @*requested-layers)) "Both fallback page layers request a higher rate")
              (is (every? #(= "highest" (:rate %)) @*requested-layers))
              (is (some #(re-find #"swap-in" (:class %)) @*requested-layers))
              (is (some #(re-find #"swapped-out" (:class %)) @*requested-layers))
              (let [fallback (js/getComputedStyle (.querySelector element ".swap-in"))]
                (doseq [pseudo ["::view-transition-old(page)" "::view-transition-new(page)"]]
                  (let [native (js/getComputedStyle (.-documentElement js/document) pseudo)]
                    (is (= "0.25s" (.-transitionDuration fallback) (.-animationDuration native))
                        "Native and fallback use the same brief production duration")
                    (is (= "linear" (.-transitionTimingFunction fallback) (.-animationTimingFunction native)))
                    (is (= "0s" (.-transitionDelay fallback) (.-animationDelay native))
                        "Neither path delays either layer"))))
              (when (.-startViewTransition js/document)
                (let [footer (.querySelector element "#footer-sticky")
                      transition (.startViewTransition js/document #(js/Promise.resolve nil))
                      style #(js/getComputedStyle (.-documentElement js/document) %)]
                  (await! (.-ready transition))
                  (is (= "sticky-footer" (.-viewTransitionName (js/getComputedStyle footer))))
                  (is (= "none" (.-display (style "::view-transition-old(sticky-footer)"))))
                  (is (= "none" (.-animationName (style "::view-transition-new(sticky-footer)"))))
                  (is (= "1" (.-opacity (style "::view-transition-new(sticky-footer)"))))
                  (is (= "none" (.-animationName (style "::view-transition-group(sticky-footer)"))))
                  (is (> (js/parseInt (.-zIndex (style "::view-transition-group(sticky-footer)")))
                         (js/parseInt (.-zIndex (style "::view-transition-group(page)"))))
                      "The unanimated footer snapshot stays above both fading page snapshots")
                  (await! (.-finished transition))
                  (is (identical? footer (.querySelector element "#footer-sticky"))
                      "Native transitions retain the mounted footer DOM")))
              (finally
                (when @*root (support/unmount! @*root))
                (.remove native-stylesheet)
                (.remove host)
                (if original-frame-rate
                  (js/Object.defineProperty js/Animation.prototype "frameRate" original-frame-rate)
                  (js/Reflect.deleteProperty js/Animation.prototype "frameRate"))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest github-pagination-component-is-not-the-default-loading-helper
  (with-redefs [rf/subscribe (fn
                              ([[query]]
                               (r/atom (case query
                                        :github/get-from ["owner" "repo"]
                                        :github/filter-by []
                                        :github/commit-count 0
                                        :github/website-url "https://github.com/owner/repo"
                                        :github/commits []
                                        nil)))
                              ([_ _] (r/atom nil)))]
    (let [html (server/render-to-string [github-views/<commits>])]
      (is (re-find #"github-loading" html))
      (is (re-find #"Loaded (?:<!-- -->)?0" html)))))

(deftest code-arrival-before-native-transition-callback-keeps-real-view
  (let [original (.-startViewTransition js/document)
        *callback (atom nil) *events (atom [])
        shell {:path "/race", :data {:view (fn [] [:div "Shell"])}}
        real (assoc-in shell [:data :view] (fn [] [:div "Real page"]))]
    (try
      (set! (.-startViewTransition js/document)
            (fn [callback]
              (reset! *callback callback)
              #js {:ready (js/Promise.resolve nil) :finished (js/Promise.resolve nil)
                   :skipTransition (fn [])}))
      (with-redefs [rf/dispatch #(swap! *events conj %)]
        (page-transition/navigate! shell true)
        (page-transition/replace-destination! real)
        (@*callback)
        (is (= real (get-in @*events [0 1])) "A delayed transition cannot reinstall its obsolete shell")
        (page-transition/replace-destination! {:path "/unrelated" :data {:view identity}})
        (is (= real (:match @page-transition/*destination)) "An unrelated route cannot change the pending destination"))
      (finally (set! (.-startViewTransition js/document) original)))))


(deftest cached-markdown-is-visible-on-its-first-commit-and-remount
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                *commits (atom [])
                <article> (r/create-class
                           {:component-did-mount
                            (fn [_]
                              (let [text (.querySelector element ".md-rendered")]
                                (swap! *commits conj
                                       {:text (.-textContent text)
                                        :opacity (.-opacity (.getComputedStyle js/window text))})))
                            :reagent-render
                            (fn [] [ui/<md->div> "Already loaded article"])})]
            (.appendChild (.-body js/document) element)
            (let [root (await! (support/create-root! element))]
              (try
                (await! (support/render! root ^{:key :feed} [<article>]))
                (await! (support/render! root nil))
                (await! (support/render! root ^{:key :permalink} [<article>]))
                (is (= 2 (count @*commits)) "Exercise both initial mount and route-style remount")
                (is (every? #(= "Already loaded article" (:text %)) @*commits))
                (is (every? #(= "1" (:opacity %)) @*commits)
                    "The first commit must not hide cached Markdown until another render")
                (finally (support/unmount! root) (.remove element))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest loaded-module-vectors-use-the-component-directly
  (let [<target> (fn [spec] [:section (:title spec)])
        module-spec {:id :vector-test :view {:post <target>}}]
    (binding [context/*server?* true context/*modules* {:vector-test module-spec}]
      (is (= [<target> {:title "SSR"}]
             (m/<> {:module :vector-test :view :post} {:title "SSR"})))
      (is (= [<target> {:title "Keyword"}]
             (m/<> :vector-test/post {:title "Keyword"})))
      (is (= [<target> {:title "Direct"}]
             (m/<> <target> {:title "Direct"}))))))

(defpage <recoverable-page> []
  (let [route @(shim/subscribe [:common/route])]
    (if (= "/page-error" (:path route))
      (throw (js/Error. "Intentional page fixture error"))
      [:section {:data-page-recovered true} "Recovered page"])))

(deftest page-boundary-recovers-on-route-change-without-remounting-the-host
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                previous (await! (state-at! [:common/route]))]
            (.appendChild (.-body js/document) element)
            (try
              (shim/dispatch [:set [:common/route] {:path "/page-error"}])
              (await! (support/render! root [<recoverable-page>]))
              (is (some? (.querySelector element "[role=alert]")))
              (shim/dispatch [:set [:common/route] {:path "/page-recovered"}])
              (await! (wait-for! #(.querySelector element "[data-page-recovered]")))
              (is (nil? (.querySelector element "[role=alert]")))
              (finally
                (shim/dispatch [:set [:common/route] previous])
                (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %)))
        (.finally done))))

(m/defc <lazy-vector-target> [{:keys [title]}]
  [:p {:data-lazy-vector-target true} title])
(m/defc <lazy-vector-consumer> [module]
  [:section (m/<> (keyword (name module) "view") {:title "Vector target"})])

(deftest mounted-consumers-share-code-acquisition-and-promote-direct-vectors
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                module (keyword (str "vector-test-" (random-uuid)))
                original-modules loader/modules original-load loader/load-code!
                *ready? (atom false) *calls (atom 0) *complete (atom nil)
                spec {:id module :view {:view <lazy-vector-target>}}
                pending (js/Promise. (fn [resolve _] (reset! *complete resolve)))
                loadable (reify lazy/ILoadable (ready? [_] @*ready?)
                          IDeref (-deref [_] spec))]
            (.appendChild (.-body js/document) element)
            (try
              (set! loader/modules (assoc original-modules module loadable))
              (set! loader/load-code!
                    (fn [_]
                      (swap! *calls inc)
                      (.then pending (fn [_]
                                       (reset! *ready? true)
                                       (shim/dispatch [:loader/code-ready module]) spec))))
              (await! (support/render! root
                                      [:div [<lazy-vector-consumer> module]
                                       [<lazy-vector-consumer> module]]))
              (await! (wait-for! #(pos? @*calls)))
              (is (= 1 @*calls) "Both mounted consumers acquire one shared module source")
              (is (nil? (.querySelector element "[data-lazy-vector-target]")))
              (@*complete nil)
              (await! (wait-for! #(= 2 (.-length (.querySelectorAll element "[data-lazy-vector-target]")))))
              (is (= 2 (.-length (.querySelectorAll element "section > p[data-lazy-vector-target]"))))
              (is (nil? (.querySelector element ".loading-container")))
              (finally
                (support/unmount! root) (.remove element)
                (set! loader/modules original-modules) (set! loader/load-code! original-load)
                (shim/dispatch [:component-data/remove [:loader :code-ready module]])))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest documentation-events-and-components-share-the-backend-resource
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                page (str "docs-test-" (random-uuid))
                html "<h1>Backend documentation</h1>"
                original-get ajax/GET
                *calls (atom []) *respond (atom nil)]
            (.appendChild (.-body js/document) element)
            (try
              (set! ajax/GET (fn [url & [options]]
                              (swap! *calls conj url)
                              (reset! *respond (:handler options))))
              ;; Route/controller event and two mounted views use the same source.
              (shim/dispatch [:docs/get page])
              (await! (support/render! root [:div [docs-view/<doc-page> page]
                                             [docs-view/<doc-page> page]]))
              (await! (wait-for! #(some? @*respond)))
              (is (= [(str "/api/doc?path=" page)] @*calls))
              (@*respond html)
              (await! (wait-for! #(= 2 (.-length (.querySelectorAll element ".codox h1")))))
              (is (= html (await! (state-at! [:docs page]))))
              (await! (support/render! root nil))
              (await! (support/render! root [docs-view/<doc-page> page]))
              (is (= 1 (count @*calls)) "Remount reuses HTML in app-db")
              (is (= "Backend documentation" (.-textContent element)))
              (finally
                (support/unmount! root) (.remove element)
                (set! ajax/GET original-get)
                (shim/dispatch [:component-data/remove [:docs page]])
                (data/invalidate! #{(docs-pages/document-dependency page)
                                    (:load (docs-pages/document-dependency page))})))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest mounted-thread-reveal-acquires-both-levels-before-child-mount
  (async done
    (-> (go-promise
          (let [restore! (rf/make-restore-fn)
                path [24 "root" "child" "folded"]
                transport (fake-scoped-transport!
                            (atom {:blog_comments
                                   [{:id "reply" :parent_post 24 :parent_comment "folded" :ts 1}
                                    {:id "grandchild" :parent_post 24 :parent_comment "reply" :ts 2}]}))
                _ (rf/dispatch-sync [:blog/expand-comment-thread path true])
                {:keys [values unmount!]} (await! (mount-subscriptions!
                                                   {:replies [:comments/reveal-thread path]}))]
            (try
              (await! (wait-for! #(some? (:replies (values)))))
              (is (= #{"reply"} (set (keys (:replies (values))))))
              (let [result (await! (subscription-value!
                                    [:comments/cached-thread 24 "reply"]))]
                (is (= "grandchild" (get-in result [:grandchild :id]))
                    "The child query was filled without mounting a child reader"))
              (finally (unmount!) ((:close! transport)) (restore!)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest hydration-suppression-ends-without-restarting-document-motion
  (let [before @restore/*context]
    (try
      (restore/begin! {:hydrate? true})
      (is (restore/skip-enter?))
      (is (restore/document-enter?))
      (restore/hydrated!)
      (is (not (restore/skip-enter?)) "Later SPA content can animate")
      (is (not (restore/initial-enter?)) "Later components have no SSR animation marker")
      (is (restore/document-enter?) "The existing document keeps its animation selector")
      (restore/begin! {:back? true})
      (restore/hydrated!)
      (is (restore/skip-enter?) "Back restoration still bypasses all entrances")
      (is (not (restore/document-enter?)))
      (finally (reset! restore/*context before)))))

(deftest restored-deep-thread-is-complete-in-the-first-mounted-commit
  (async done
    (-> (go-promise
          (let [restore! (rf/make-restore-fn)
                context @restore/*context
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                rows (mapv (fn [[id parent]]
                             {:id id :parent-post 42 :parent-comment parent
                              :title id :text "Cached comment" :ts 1
                              :reply-count (if (= id "leaf") 0 1)})
                           [["root" nil] ["child" "root"] ["grandchild" "child"]
                            ["deep" "grandchild"] ["leaf" "deep"]])]
            (try
              (restore/begin! {:back? true})
              (rf/dispatch-sync [:blog/expand-comment-thread [42 "root" "child" "grandchild"] true])
              (doseq [parent [nil "root" "child" "grandchild" "deep"]]
                (let [query (if parent (comments/thread-query 42 parent)
                                (comments/root-query 42 comments/page-size))]
                  (rf/dispatch-sync [:store/scoped (scoped/query-key query)
                                     {:docs (mapv #(hash-map :id (:id %) :data %)
                                                  (filter #(= parent (:parent-comment %)) rows))}])))
              ;; This test build bundles the real user module rather than using
              ;; Shadow's split browser module runtime. Only adapt code readiness;
              ;; comment subscriptions, caches and rendered components remain real.
              (with-redefs [loader/modules
                            {:user (reify lazy/ILoadable
                                     (ready? [_] true)
                                     IDeref
                                     (-deref [_] (dissoc user-module/spec :content :depends)))}]
                (await! (support/render! root [blog-views/<comments-section> {:post {:id 42}}])))
              (is (= 5 (.-length (.querySelectorAll element ".blog-comment-title")))
                  (str "All restored depths render together, through real subscriptions: " (.-textContent element)))
              (is (zero? (.-length (.querySelectorAll element ".appear-wrapper:not(.appeared)"))))
              (is (zero? (.-length (.querySelectorAll element ".component-spinner"))))
              (finally (support/unmount! root) (reset! restore/*context context) (restore!)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
