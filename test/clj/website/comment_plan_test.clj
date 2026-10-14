(ns website.comment-plan-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [website.modules.blog.comments :as comments]
            [website.modules.blog.data :as data]
            [website.supabase.plan :as plan]
            [website.supabase.query :as query]))

(def old-root {:id "root", :parent-post 24, :parent-comment nil, :path [24], :ts 0, :reply-count 1})
(def child {:id "child", :parent-post 24, :parent-comment "root", :path [24 "root"], :ts 1, :reply-count 1})
(def target {:id "113", :parent-post 24, :parent-comment "child", :path [24 "root" "child"], :ts 2, :reply-count 1})
(def descendant {:id "114", :parent-post 24, :parent-comment "113", :path [24 "root" "child" "113"], :ts 3, :reply-count 0})
(def neighbors (mapv #(hash-map :id (str "new-" %)
                               :parent-post 24
                               :parent-comment nil
                               :path [24]
                               :ts (+ 100 %)
                               :reply-count 0) (range 20)))
(def contract
  {"blog-posts" {24 {:id 24, :title "Parent post", :text "Body", :permalink "parent-24", :tags []}}
   "blog-comments" (into {} (map (juxt :id identity)) (concat neighbors [old-root child target descendant]))})

(defn evaluate [selection]
  (let [requests (atom [])
        result (plan/evaluate data/plan selection
                 (fn [queries]
                   (swap! requests into queries)
                   (mapv #(or (query/query-contract contract %) {:docs []}) queries)))]
    (assoc result :requests @requests)))

(deftest focus-acquires-an-old-root-outside-the-current-comment-window
  (let [result (evaluate {:post-id 24, :comment-id "113"})
        roots (data/page-roots (:values result))
        snapshot (data/snapshot result 1000)
        expansion (get-in (edn/read-string (:app-db-edn snapshot)) [:state :blog :comment-thread-expanded])]
    (is (:ready? result))
    (is (= 11 (count roots)) "The normal ten roots and the focused old thread are available")
    (is (some #(= "root" (:id %)) roots))
    (is (some #(= "113" (:id %)) (:comments snapshot)))
    (is (some #(= "114" (:id %)) (:comments snapshot))
        "Reply levels opened by the focused ancestry are ready for first hydration")
    (is (true? (get expansion [24 "root"])))
    (is (true? (get expansion [24 "root" "child"])))
    (is (some #(= [[:id :== "child"] [:parent-post :== 24]] (:where %)) (:requests result)))))

(deftest standalone-reads-only-the-selected-subtree-and-a-parent-summary
  (let [result (evaluate {:comment-id "113", :standalone-comment? true})
        snapshot (data/snapshot result 1000)]
    (is (:ready? result))
    (is (= #{"113" "114"} (set (map :id (:comments snapshot)))))
    (is (= [24] (mapv :id (:posts snapshot))))
    (is (= 24 (:post-id snapshot)))
    (is (empty? (:summaries snapshot)))
    (is (empty? (get-in (edn/read-string (:app-db-edn snapshot)) [:state :blog :comment-thread-expanded]))
        "Standalone snapshots do not expand hidden ancestry in the full post")
    (is (not-any? #(some #{[:parent-comment :== nil]} (:where %)) (:requests result))
        "No neighboring root query is acquired")
    (is (some #(and (:summary? %) (= [[:id :== 24]] (:where %))) (:requests result)))
    (is (not-any? #(= [[:id :== "child"] [:parent-post :== 24]] (:where %)) (:requests result))
        "Hidden ancestors are not fetched for a standalone comment")))

(deftest missing-and-wrong-post-comments-do-not-acquire-a-different-post
  (let [standalone (data/snapshot (evaluate {:comment-id "missing", :standalone-comment? true}) 1000)
        focused (data/snapshot (evaluate {:post-id 25, :comment-id "113"}) 1000)]
    (is (:missing? standalone))
    (is (:missing-comment? standalone))
    (is (empty? (:posts standalone)))
    (is (:missing-comment? focused))
    (is (not-any? #(= "113" (:id %)) (:comments focused)))))

(deftest default-focus-expansion-retains-explicit-folds-after-navigation
  (is (false? (get (comments/focus-expansion {[24 "root"] false} target false) [24 "root"])))
  (is (true? (get (comments/focus-expansion {[24 "root"] false} target) [24 "root"])))
  (is (= [[24 "root"] [24 "root" "child"]] (comments/ancestor-paths target))))

(deftest ordinary-feed-snapshots-retain-page-selection
  (let [snapshot (data/snapshot (evaluate {:page 1}) 1000)]
    (is (= 1 (:page snapshot)))
    (is (nil? (:post-id snapshot))
        "The first feed post must not turn the feed into a selected-post snapshot")
    (is (false? (:standalone-comment? snapshot)))))
