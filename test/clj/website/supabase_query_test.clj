(ns website.supabase-query-test
  (:require [clojure.test :refer [deftest is]]
            [website.supabase.query :as query]
            [website.build.modules :as modules]
            [website.build.site :as site]
            [website.modules.blog.data :as data]
            [website.modules.blog.comments :as comments]))

(def summaries
  {:docs [{:id "legacy-a", :data {:id 28, :title "First"}}
          {:id "legacy-b", :data {:id 27, :title "Second"}}
          {:id "legacy-c", :data {:id 26, :title "Third"}}]})

(deftest summary-counts-use-numeric-post-ids-and-only-root-metadata
  (is (= {:table "blog_comments"
          :select "id,parent_post"
          :filters [[:parent_post "in" [28 27 26]] [:parent_comment "is" nil]]}
         (query/reply-count-plan data/summaries-query summaries)))
  (is (= "doc_id,id,permalink,user_id,title,tags,score,ts"
         (:select (first (query/seed-load-plan data/summaries-query)))))
  (is (= #{"blog_posts" "blog_comments"}
         (set (query/realtime-tables data/summaries-query)))))

(deftest summary-counts-preserve-document-identity-and-zero-counts
  (let [result (query/with-reply-counts data/summaries-query summaries
                                      [{:id "a", :parent_post 28}
                                       {:id "b", :parent_post 28}
                                       {:id "c", :parent_post 27}])]
    (is (= [2 1 0] (mapv #(get-in % [:data :comment-count]) (:docs result))))
    (is (= ["legacy-a" "legacy-b" "legacy-c"] (mapv :id (:docs result))))
    (is (= ["First" "Second" "Third"] (mapv #(get-in % [:data :title]) (:docs result))))
    (is (every? #(not (contains? (:data %) :reply-count)) (:docs result)))
    (is (every? #(not (contains? (:data %) :comment-count)) (:docs summaries)))
    (is (= [0 0 0] (mapv #(get-in % [:data :comment-count])
                              (:docs (query/with-reply-counts data/summaries-query summaries [])))))))

(deftest existing-reply-count-requests-and-results-retain-their-contract
  (let [opts (comments/thread-query 42 "parent")
        value {:docs [{:id "a", :data {:id "a"}} {:id "b", :data {:id "b"}}]}
        result (query/with-reply-counts opts value [{:id "c", :parent_comment "a"}])]
    (is (= {:table "blog_comments"
            :select "id,parent_comment"
            :filters [[:parent_comment "in" ["a" "b"]]]}
           (query/reply-count-plan opts value)))
    (is (= [1 0] (mapv #(get-in % [:data :reply-count]) (:docs result))))
    (is (every? #(not (contains? (:data %) :comment-count)) (:docs result)))
    (is (= ["blog_comments"] (query/realtime-tables opts)))))

(deftest absent-count-intent-and-empty-results-do-not-read-related-tables
  (is (nil? (query/reply-count-plan data/summaries-query {:docs []})))
  (is (nil? (query/reply-count-plan (dissoc data/summaries-query :reply-counts?) summaries)))
  (is (= ["blog_posts"] (query/realtime-tables (dissoc data/summaries-query :reply-counts?))))
  (is (= ["blog_posts"] (query/realtime-tables (data/post-query 28))))
  (is (= {:docs []} (query/with-reply-counts data/summaries-query {:docs []} []))))

(deftest post-count-projection-follows-the-comments-capability
  (try
    (doseq [comments? [true false]]
      (with-redefs [site/settings (constantly {:modules {:blog {:comments comments?}}})]
        (let [definitions ((deref #'modules/storage-definitions) nil nil)
              tables (:blog definitions)
              post-table (first (filter #(= :blog_posts (:table %)) tables))]
          (is (= comments? (boolean (some #(= :blog_comments (:table %)) tables))))
          (is (= comments? (contains? (get-in post-table [:queries "blog-posts"]) :reply-count))))))
    (finally (require 'website.modules.blog.storage :reload))))
