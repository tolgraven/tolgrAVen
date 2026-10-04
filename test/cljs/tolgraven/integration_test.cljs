(ns tolgraven.integration-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [reagent.ratom :as ratom]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [react-dom :as react-dom]
            [tolgraven.supabase.client :as supabase]
            [tolgraven.supabase.query :as supabase-query]
            [tolgraven.store.contract :as store-contract]
            [reitit.core :as reitit]
            [tolgraven.routes :as routes]
            [tolgraven.loader :as loader]
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
          (is (some #(= [:error-page] (second %)) @*events))
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
          render (loader/<>)
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
                     (render spec :first {:second true}))
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
        render (loader/<>)]
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
