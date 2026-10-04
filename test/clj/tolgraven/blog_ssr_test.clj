(ns tolgraven.blog-ssr-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [tolgraven.blog.ssr :as ssr]
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

(deftest post-snapshot-reads-one-post-and-no-comments-or-private-columns
  (let [*calls (atom [])]
    (with-redefs [content/fresh-bundle! (fn [_] {:content {:header {:text ["Test" []]}}})
                  supabase/request! (fn [_ table {:keys [query-params]}]
                                      (swap! *calls conj [table query-params])
                                      {:body (case table
                                               "blog_posts" [{:id 42 :user_id "u1" :title "Hi"
                                                              :text "Body" :ts 0 :tags "one two"
                                                              :secret "never serialize"}]
                                               "site_users" [{:id "u1" :name "Name" :email "private"}])})]
      (let [snapshot (ssr/snapshot! "/blog/post/hi-42" {:post-id 42})]
        (is (= "eq.42" (get-in @*calls [0 1 "id"])))
        (is (= ["blog_posts" "site_users"] (mapv first @*calls)))
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
                ssr/page! (fn [_] {:html "<article>Safe rendered content</article>"
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
