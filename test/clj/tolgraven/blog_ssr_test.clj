(ns tolgraven.blog-ssr-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [tolgraven.ssr :as ssr]
            [tolgraven.page-router :as pages]
            [tolgraven.page :as page]
            [tolgraven.layout :as layout]
            [tolgraven.config :as config]
            [optimus.html :as optimus-html]
            [tolgraven.content.service :as content]
            [tolgraven.platform.supabase :as supabase]
            [tolgraven.supabase.query :as query]
            [tolgraven.concurrent :as concurrent]
            [tolgraven.supabase.reader :as reader]
            [tolgraven.supabase.plan :as plan]
            [tolgraven.blog.data :as blog-data]))

(deftest scoped-blog-plans-filter-at-the-database
  (is (= [{:seed-key :blog_posts :table "blog_posts"
           :select (query/public-columns "blog_posts") :filters [[:id "eq" 42]]}]
         (query/seed-load-plan {:scoped? true :path-collection [:blog-posts] :where [[:id :== 42]]})))
  (is (= [[:parent_post "eq" 42] [:parent_comment "is" nil]]
         (:filters (first (query/seed-load-plan
                            {:scoped? true :path-collection [:blog-comments]
                             :where [[:parent-post :== 42] [:parent-comment :== nil]]})))))
  (is (not (some #{"text"} (string/split (:select (first (query/seed-load-plan
                                                         {:scoped? true :summary? true
                                                          :path-collection [:blog-posts]}))) #",")))))

(deftest render-cache-compares-fresh-snapshots-and-does-not-cache-errors
  (let [*snapshot (atom {:posts [{:id 1 :text "First"}]}) *renders (atom 0)]
    (reset! ssr/*cache {})
    (with-redefs [ssr/snapshot! (fn [_ _] @*snapshot)
                  ssr/render! (fn [snapshot] (swap! *renders inc) (str snapshot))]
      (is (= :miss (:cache (ssr/page! "/blog"))))
      (is (= :hit (:cache (ssr/page! "/blog"))))
      (swap! *snapshot assoc-in [:posts 0 :text] "Edited")
      (is (= :miss (:cache (ssr/page! "/blog"))))
      (swap! *snapshot assoc :posts [])
      (is (= :miss (:cache (ssr/page! "/blog"))))
      (is (= 3 @*renders)))
    (with-redefs [ssr/snapshot! (fn [_ _] (throw (ex-info "Offline" {})))]
      (is (thrown? Exception (ssr/page! "/blog"))))
    (reset! ssr/*cache {})))

(deftest post-snapshot-reads-only-selected-post-threads-and-public-columns
  (let [*calls (atom [])]
    (with-redefs [content/fresh-bundle! (fn [_] {:content {:header {:text ["Test" []]}}})
                  supabase/request! (fn [_ table {:keys [query-params]}]
                                      (swap! *calls conj [table query-params])
                                      {:body (case table
                                               "blog_posts" [{:id 42 :doc_id "42" :user_id "u1" :title "Hi"
                                                              :text "Body" :ts 0 :tags "one two"
                                                              :secret "never serialize"}]
                                               "blog_comments" (if (= "is.null" (get query-params "parent_comment")) [{:id "c1" :parent_post 42 :user_id "u1" :text "SSR comment" :ts 0 :secret "private"}] [])
                                               "site_users" [{:id "u1" :name "Name" :email "private"}]
                                               "auth_roles" [])})]
      (let [snapshot (ssr/snapshot! "/blog/post/hi-42" {:post-id 42})]
        (is (some #(= "eq.42" (get-in % [1 "id"])) @*calls))
        (is (= {"blog_posts" 2 "blog_comments" 2 "site_users" 1 "auth_roles" 1} (frequencies (map first @*calls))))
        (is (some #(= "eq.42" (get-in % [1 "parent_post"])) @*calls))
        (is (= "SSR comment" (get-in snapshot [:comments 0 :text])))
        (is (some #(and (= "blog_posts" (first %)) (not (string/includes? (get-in % [1 "select"]) "text"))) @*calls)
            "Pagination and tags use only summaries, never every post body")
        (is (= {:id "u1" :name "Name"} (get-in snapshot [:posts 0 :author])))
        (is (not (string/includes? (pr-str snapshot) "secret")))
        (is (= "1970-01-01" (get-in snapshot [:posts 0 :date])))))))

(deftest route-parsing-is-bounded-and-does-not-intercept-other-blog-pages
  (is (= {:post-id 42} (ssr/route "/blog/post/hello-42")))
  (is (= {:page 2} (ssr/route "/blog/page/2")))
  (doseq [uri ["/blog/new-post" "/blog/archive" "/blog/page/0" "/blog/page/99999999"
               "/blog/post/no-id" "/blog/post/foo/42"]]
    (is (nil? (ssr/route uri)))))

(deftest jvm-worker-protocol-renders-successive-isolated-requests
  (let [snapshot (json/read-str (slurp "test/browser/blog-ssr-input.json") :key-fn keyword)]
    (try
      (is (string/includes? (ssr/render! snapshot) "Server-rendered blog"))
      (is (not (string/includes? (ssr/render! (assoc snapshot :posts [])) "Server-rendered blog")))
      (is (string/includes? (ssr/render! snapshot) "<strong>article</strong>"))
      (finally (ssr/stop-worker!)))))

(deftest layout-escapes-post-titles-and-embeds-the-matching-public-snapshot
  (with-redefs [config/env {:dev true}
                ssr/enabled? (constantly true)
                ssr/page! (fn [& _] {:html "<article>Safe rendered content</article>"
                                   :snapshot {:posts [{:title "</title><script>bad()</script>"}]
                                              :content {}}})
                optimus-html/link-to-js-bundles (fn [& _] "")]
    (let [response (layout/render-home {:uri "/blog/post/test-1"})
          html (:body response)]
      (is (= 200 (:status response)))
      (is (= "no-store" (get-in response [:headers "Cache-Control"])))
      (is (string/includes? html "data-hydrate=\"true\""))
      (is (string/includes? html "<article>Safe rendered content</article>"))
      (is (string/includes? html "&lt;/title&gt;&lt;script&gt;"))
      (is (not (string/includes? html "<script>bad()"))))))

(deftest landing-routes-load-complete-cms-content-without-supabase
  (let [*requested (atom []) *title (atom "Landing")]
    (reset! ssr/*cache {})
    (with-redefs [supabase/request! (fn [& _] (throw (ex-info "Landing must not read Supabase" {})))
                  content/fresh-bundle! (fn [keys]
                                          (swap! *requested conj (set keys))
                                          {:content {:intro {:title @*title}}})
                  ssr/render! (fn [snapshot] (get-in snapshot [:content :intro :title]))]
      (doseq [path ["/" "/about" "/services" "/hire"]]
        (is (= {:kind :landing} (ssr/route path)))
        (is (= :landing (:kind (ssr/snapshot! path (ssr/route path))))))
      (is (every? #(every? % [:intro :services :story :moneyshot :gallery :header :footer]) @*requested))
      (is (= :miss (:cache (ssr/page! "/"))))
      (is (= :hit (:cache (ssr/page! "/"))))
      (reset! *title "Edited in Strapi")
      (is (= "Edited in Strapi" (:html (ssr/page! "/"))))
      (is (nil? (ssr/route "/user/private"))))
    (reset! ssr/*cache {})))

(deftest registered-pages-share-the-render-cache-without-page-type-branches
  (let [*renders (atom 0) *value (atom "First")
        spec {:ssr true :module :example :data-source ::example}]
    (defmethod ssr/page-data! ::example [_ uri selection]
      {:content {} :app-db-edn (pr-str {:example {:value @*value}})})
    (reset! ssr/*cache {})
    (try
      (with-redefs [pages/match (fn [_] {:data spec :path-params {}})
                    ssr/render! (fn [snapshot] (swap! *renders inc) (:app-db-edn snapshot))]
        (is (= {} (ssr/route "/example")))
        (is (= :miss (:cache (ssr/page! "/example"))))
        (is (= :hit (:cache (ssr/page! "/example"))))
        (is (= 1 @*renders))
        (reset! *value "Changed")
        (is (= :miss (:cache (ssr/page! "/example"))))
        (is (= 2 @*renders)))
      (finally (remove-method ssr/page-data! ::example) (reset! ssr/*cache {})))))

(deftest additional-module-routes-declare-ssr-and-bound-their-inputs
  (is (= {} (ssr/route "/cv")))
  (is (= {:doc "index"} (ssr/route "/docs")))
  (is (= {:doc "tolgraven.core"} (ssr/route "/docs/codox/tolgraven.core")))
  (is (nil? (ssr/route "/docs/codox/.."))))

(deftest page-selection-does-not-depend-on-server-rendering
  (let [match (pages/match "/blog/post/hi-42")]
    (is (= {:post-id 42} (page/selection (assoc-in match [:data :ssr] false))))))

(deftest bounded-comments-and-bulk-authors
  (let [calls (atom [])
        roots (mapv #(hash-map :id (str "r" %) :parent_post 42 :user_id (str "u" %) :ts % :text "root") (range 11))
        children (mapv #(hash-map :id (str "c" %) :parent_post 42 :parent_comment (str "r" (inc %)) :user_id (str "v" %) :ts % :text "child") (range 10))]
    (with-redefs [content/fresh-bundle! (fn [_] {:content {}})
                  supabase/request! (fn [_ table {:keys [query-params]}]
                                      (swap! calls conj [table query-params])
                                      {:body (case table
                                               "blog_posts" [{:id 42 :doc_id "42" :user_id "author"}]
                                               "blog_comments" (cond
                                                                 (= "is.null" (get query-params "parent_comment")) roots
                                                                 (= "id,parent_comment" (get query-params "select"))
                                                                 (if (string/includes? (get query-params "parent_comment") "c0")
                                                                   [{:id "deep" :parent_comment "c0"}]
                                                                   (mapv #(select-keys % [:id :parent_comment]) children))
                                                                 :else children)
                                               "site_users" [{:id "author" :name "Author"}]
                                               "auth_roles" [])})]
      (let [snapshot (ssr/snapshot! "/blog/post/test-42" {:post-id 42})
            user-reads (filter #(= "site_users" (first %)) @calls)
            root-read (second (first (filter #(= "is.null" (get-in % [1 "parent_comment"])) @calls)))]
        (is (= 1 (count user-reads)) "Authors are fetched together, not once per commenter")
        (is (string/starts-with? (get-in (first user-reads) [1 "id"]) "in.("))
        (is (= 11 (get root-read "limit")))
        (is (= "ts.desc,id.desc" (get root-read "order")))
        (is (= 21 (count (:comments snapshot))))
        (is (not-any? #(= "deep" (:id %)) (:comments snapshot)))
        (is (= 1 (:reply-count (first (filter #(= "c0" (:id %)) (:comments snapshot))))))))))

(deftest saved-return-uses-client-restoration-without-server-data-reads
  (with-redefs [config/env {:dev true} ssr/enabled? (constantly true)
                ssr/page! (fn [& _] (throw (ex-info "Must not render" {})))
                content/bundle! (fn [] (throw (ex-info "Must not fetch CMS" {})))
                optimus-html/link-to-js-bundles (fn [& _] "")]
    (let [response (layout/render-home {:uri "/blog" :cookies {"tolgraven-return" {:value "%2Fblog"}}})]
      (is (= 200 (:status response)))
      (is (string/includes? (:body response) "data-restore=\"true\""))
      (is (not (string/includes? (:body response) "data-hydrate")))
      (is (= "no-store" (get-in response [:headers "Cache-Control"]))))))

(deftest saved-return-hints-cover-recent-paths-and-reject-invalid-values
  (is (layout/returning-page? {:uri "/blog" :cookies {"tolgraven-return" {:value "[\"/\",\"/blog\"]"}}}))
  (is (not (layout/returning-page? {:uri "/cv" :cookies {"tolgraven-return" {:value "[\"/\",\"/blog\"]"}}})))
  (is (not (layout/returning-page? {:uri "/blog" :cookies {"tolgraven-return" {:value "%invalid"}}}))))

(deftest concurrent-pages-share-identical-flights-without-serializing-other-pages
  (let [entered (java.util.concurrent.CountDownLatch. 2)
        release (promise) snapshots (atom []) renders (atom [])]
    (reset! ssr/*cache {})
    (try
      (with-redefs [ssr/snapshot! (fn [uri _] (swap! snapshots conj uri) {:path uri :posts []})
                    ssr/render! (fn [snapshot]
                                  (swap! renders conj (:path snapshot))
                                  (.countDown entered)
                                  (deref release 5000 nil)
                                  (:path snapshot))]
        (let [a (ssr/page-async! "/blog") duplicate (ssr/page-async! "/blog")
              b (ssr/page-async! "/blog/page/2")]
          (is (identical? a duplicate))
          (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS)
              "Two different paths reach rendering before either completes")
          (deliver release true)
          (is (= "/blog" (:html (concurrent/await! a))))
          (is (= "/blog/page/2" (:html (concurrent/await! b))))
          (is (= 2 (count @snapshots)))
          (is (= 2 (count @renders)))
          (is (= :hit (:cache (ssr/page! "/blog"))))))
      (finally (deliver release true) (reset! ssr/*cache {})))))

(deftest async-ring-boundary-returns-before-work-and-preserves-bindings
  (let [entered (promise) release (promise) response (promise)
        handler (concurrent/wrap-async (fn [_] (deliver entered true) @release {:status 200 :body *print-length*}))]
    (try
      (binding [*print-length* 17]
        (is (nil? (handler {} #(deliver response %) #(deliver response %)))))
      (is (= true (deref entered 1000 :timeout)))
      (is (not (realized? response)))
      (deliver release true)
      (is (= {:status 200 :body 17} (deref response 1000 {})))
      (finally (deliver release true)))))

(deftest shared-read-plan-batches-independent-nodes-before-dependent-reads
  (let [calls (atom [])
        nodes [{:id :left :queries (fn [_] [{:path-collection [:users]}])}
               {:id :right :queries (fn [_] [{:path-collection [:blog-posts]}])}
               {:id :child :depends [:left :right]
                :queries (fn [_] [{:path-collection [:blog-comments]}])}]
        result (plan/evaluate nodes {} (fn [queries] (swap! calls conj queries)
                                         (mapv (constantly {:docs []}) queries)))]
    (is (= [2 1] (mapv count @calls)))
    (is (:ready? result))
    (is (= {:left [] :right [] :child []} (:values result)))
    (is (not (:ready? (plan/evaluate nodes {} #(mapv (constantly nil) %)))))))

(deftest independent-public-reads-run-concurrently
  (let [entered (java.util.concurrent.CountDownLatch. 2) release (promise)]
    (try
      (with-redefs [supabase/request! (fn [& _] (.countDown entered) (deref release 5000 nil) {:body []})]
        (let [task (concurrent/submit! #(reader/read-many! [{:scoped? true :path-collection [:blog-posts]}
                                                           (query/profile-query "u")]))]
          (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS))
          (deliver release true)
          (is (= [{:docs []} {:docs []}] (concurrent/await! task)))))
      (finally (deliver release true)))))

(deftest ssr-is-enabled-by-default-and-configurable-through-edn
  (with-redefs [config/env {}]
    (is (ssr/enabled?))
    (is (= "target/ssr/site.js" (ssr/worker-path))))
  (with-redefs [config/env {:ssr {:enabled false :worker "/configured/site.js" :render-workers 3}}]
    (is (false? (ssr/enabled?)))
    (is (= "/configured/site.js" (ssr/worker-path)))
    (is (= 3 (:render-workers (ssr/settings))))))


(deftest blog-pages-and-tags-use-filtered-bulk-queries
  (let [page (first (query/seed-load-plan (blog-data/page-query 2 3)))
        tag (first (query/seed-load-plan (blog-data/tag-query "cljs")))
        contract {"blog-posts" {"1" {:id 1 :tags "cljs clojure"}
                               "2" {:id 2 :tags "cljs-more"}
                               "3" {:id 3 :tags "work\tcljs"}
                               "4" {:id 4 :tags "work"}}}]
    (is (= 6 (:offset page)))
    (is (= 3 (:limit page)))
    (is (= [[:id :desc]] (:order-by page)))
    (is (= [[:tags "match" "(^|\\s)cljs(\\s|$)"]] (:filters tag)))
    (is (= [3 1] (mapv (comp :id :data) (:docs (query/query-contract contract (blog-data/tag-query "cljs"))))))
    (is (= [2 1] (mapv (comp :id :data) (:docs (query/query-contract contract (blog-data/page-query 1 2))))))
    (is (= "(^|\\s)c\\+\\+(\\s|$)" (query/tag-pattern "c++")))
    (is (empty? (query/document-caches {:summary? true :path-collection [:blog-posts]}
                                     {:docs [{:id "1" :data {:id 1}}]})))
    (is (= {:docs [{:id "1" :data {:id 1 :text "Ready"}}]}
           (get (query/document-caches (blog-data/page-query 0 3)
                                      {:docs [{:id "1" :data {:id 1 :text "Ready"}}]})
                (pr-str (query/normalize-query (blog-data/post-query 1))))))))
