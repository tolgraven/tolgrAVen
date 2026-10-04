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
            [tolgraven.supabase.query :as query]))

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
                                               "blog_posts" [{:id 42 :user_id "u1" :title "Hi"
                                                              :text "Body" :ts 0 :tags "one two"
                                                              :secret "never serialize"}]
                                               "blog_comments" [{:id "c1" :parent_post 42 :user_id "u1" :text "SSR comment" :ts 0 :secret "private"}]
                                               "site_users" [{:id "u1" :name "Name" :email "private"}]
                                               "auth_roles" [])})]
      (let [snapshot (ssr/snapshot! "/blog/post/hi-42" {:post-id 42})]
        (is (= "eq.42" (get-in @*calls [0 1 "id"])))
        (is (= ["blog_posts" "blog_posts" "blog_comments" "site_users" "auth_roles"] (mapv first @*calls)))
        (is (= "eq.42" (get-in @*calls [2 1 "parent_post"])))
        (is (= "SSR comment" (get-in snapshot [:comments 0 :text])))
        (is (not (string/includes? (get-in @*calls [1 1 "select"]) "text"))
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
