(ns tolgraven.supabase-realtime-test
  (:require [clojure.test :refer :all]
            [tolgraven.supabase.realtime :as realtime]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.shape :as shape]))

(defn change [event row]
  {:table "blog_comments" :eventType event
   (if (= "DELETE" event) :old :new) row})

(defn top-comment [seed]
  (query/query-contract (shape/seed->contract seed)
    {:path-collection [:blog-comments] :where [[:parent-post :== 7]]
     :order-by [[:score :desc]] :limit 1}))

(deftest streamed-changes-recompute-filter-order-limit-and-nested-posts
  (let [seed (assoc realtime/empty-seed
                   :blog_posts [{:id 7 :doc_id "7" :text "Post"}]
                   :blog_comments [{:id "a" :parent_post 7 :score 2}])
        inserted (realtime/apply-change seed (change "INSERT" {:id "b" :parent_post 7 :score 3 :raw {:secret true}}))
        updated (realtime/apply-change inserted (change "UPDATE" {:id "a" :parent_post 7 :score 4}))
        moved (realtime/apply-change updated (change "UPDATE" {:id "a" :parent_post 8 :score 4}))
        deleted (realtime/apply-change moved (change "DELETE" {:id "b"}))]
    (is (= "b" (get-in (top-comment inserted) [:docs 0 :id])))
    (is (= "a" (get-in (top-comment updated) [:docs 0 :id])))
    (is (= "b" (get-in (top-comment moved) [:docs 0 :id])))
    (is (= [] (:docs (top-comment deleted))))
    (is (not (contains? (last (:blog_comments inserted)) :raw)))
    (is (= #{"a" "b"} (set (keys (get-in (shape/seed->contract inserted) ["blog-posts" "7" :comments])))))))

(deftest snapshot-replays-writes-that-arrived-during-loading
  (let [buffer [(change "UPDATE" {:id "a" :parent_post 7 :score 4})
                (change "INSERT" {:id "b" :parent_post 7 :score 5})
                (change "DELETE" {:id "c"})]
        seed (realtime/install-snapshot realtime/empty-seed "blog_comments"
               [{:id "a" :parent_post 7 :score 1} {:id "c" :parent_post 7 :score 2}] buffer)]
    (is (= #{"a" "b"} (set (map :id (:blog_comments seed)))))
    (is (= 4 (:score (first (:blog_comments seed)))))
    (is (= "b" (get-in (top-comment seed) [:docs 0 :id])))))

(deftest partial-events-use-primary-keys-and-protect-private-columns
  (let [seed (assoc realtime/empty-seed :users [{:id "u" :name "Name" :karma 1}])
        updated (realtime/apply-change seed {:table "site_users" :eventType "UPDATE"
                                            :new {:id "u" :karma 2 :email "private" :voted {:c 1}}})]
    (is (= [{:id "u" :name "Name" :karma 2}] (:users updated)))
    (is (= seed (realtime/apply-change seed {:table "site_users" :eventType "UPDATE" :new {:karma 2}})))
    (is (= seed (realtime/apply-change seed {:table "service_configs" :eventType "INSERT" :new {:secret true}})))
    (is (= seed (realtime/apply-change seed {:table "site_users" :eventType "UNKNOWN" :new {:id "u"}})))
    (is (= [] (:users (realtime/apply-change updated {:table "site_users" :eventType "DELETE" :old {:id "u"}}))))))

(deftest private-document-identities-do-not-collide-between-owners
  (let [seed (realtime/install-snapshot realtime/empty-seed "user_documents"
               [{:owner_id "a" :collection "gpt-threads" :doc_id "thread" :data {:messages []}}
                {:owner_id "b" :collection "gpt-threads" :doc_id "thread" :data {:messages []}}] [])
        seed (realtime/apply-change seed {:table "user_documents" :eventType "DELETE"
                                         :old {:owner_id "a" :collection "gpt-threads" :doc_id "thread"}})]
    (is (= ["b"] (mapv :owner_id (:store_documents seed))))
    (is (= ["user_documents"] (query/realtime-tables {:path-collection [:gpt-threads]})))
    (is (query/direct-read-query? {:path-collection [:gpt-threads]}))
    (is (not (query/public-read-query? {:path-collection [:gpt-threads]})))))

(deftest primary-key-updates-remove-the-old-cache-identity
  (let [seed (assoc realtime/empty-seed :users [{:id "before" :name "User"}])
        changed (realtime/apply-change seed {:table "site_users" :eventType "UPDATE"
                                            :old {:id "before"} :new {:id "after" :name "Updated"}})]
    (is (= [{:id "after" :name "Updated"}] (:users changed)))))
