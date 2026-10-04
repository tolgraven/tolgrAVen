(ns tolgraven.supabase-scoped-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.ratom :as ratom]
            [re-frame.db :as db]
            [tolgraven.supabase.scoped :as scoped]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.connection :as connection]
            [tolgraven.service-status :as status]
            [tolgraven.supabase-client-test :as mocks]))

(deftest readers-coalesce-refreshes-and-release-streams-without-evicting-content
  (async done
    (let [mock (mocks/mock-client) before @db/app-db
          *reads (atom []) *callbacks (atom [])
          opts {:scoped? true :path-collection [:blog-posts] :where [[:id :== 42]]}
          old @scoped/*transport]
      (scoped/connect! (:sdk mock)
                       (fn [query success error]
                         (swap! *reads conj query) (swap! *callbacks conj [success error])))
      (let [reader (scoped/ensure-query! opts)]
        (is (identical? reader (scoped/ensure-query! opts)))
        (scoped/drain!)
        (is (= [opts] @*reads))
        ((ffirst @*callbacks) {:docs [{:id "42" :data {:id 42 :title "Ready"}}]})
        (-> (mocks/tick!)
            (.then (fn [_]
                     (is (= "Ready" (get-in @reader [:docs 0 :data :title])))
                     (let [change @(get-in @(:channels mock) ["blog-scoped-blog_posts" :change])]
                       (change #js {}) (change #js {}))
                     (scoped/drain!)
                     (is (= 2 (count @*reads)) "Invalidations in one tick share one filtered read")
                     (ratom/dispose! reader)
                     (scoped/drain!)
                     (is (= 1 (count @(:removed mock))))
                     ;; A late response from an unmounted reader cannot overwrite
                     ;; retained state or recreate a stream.
                     ((first (second @*callbacks)) {:docs []})
                     (mocks/tick!)))
            (.then (fn [_]
                     (is (= "Ready" (get-in @db/app-db [:store :scoped (scoped/query-key opts)
                                                       :docs 0 :data :title])))))
            (.catch #(is false (str %)))
            (.finally (fn [] (ratom/dispose! reader)
                        (scoped/connect! (:client old) (:read! old))
                        (reset! db/app-db before) (done))))))))

(deftest connection-negotiation-is-quiet-until-failure-or-a-live-loss
  (let [id [:test-connection (random-uuid)]
        monitor (connection/watch! {:id id :title "Test connection"})
        state! (:status! monitor)]
    (try
      (doseq [state ["CONNECTING" "CHANNEL_ERROR" "CLOSED"]] (state! state))
      (is (not (contains? @status/*failures id)) "Initial transient negotiation is silent")
      (state! "SUBSCRIBED")
      (is (not (contains? @status/*failures id)))
      (state! "CLOSED")
      (is (contains? @status/*failures id) "Losing an established connection is visible")
      (state! "SUBSCRIBED")
      (is (not (contains? @status/*failures id)) "Reconnection clears the outage")
      ((:close! monitor))
      (state! "TIMED_OUT")
      (is (not (contains? @status/*failures id)) "Intentional disposal ignores late callbacks")
      (finally ((:close! monitor))))))

(deftest initial-connection-failure-is-bounded-and-disposal-cancels-the-deadline
  (async done
    (let [failed-id [:test-deadline (random-uuid)] closed-id [:test-closed (random-uuid)]
          failed (connection/watch! {:id failed-id :title "Test connection" :timeout-ms 10})
          closed (connection/watch! {:id closed-id :title "Test closed" :timeout-ms 10})]
      ((:close! closed))
      (is (not (contains? @status/*failures failed-id)))
      (js/setTimeout
       (fn []
         (is (contains? @status/*failures failed-id) "A connection that never joins reports failure")
         (is (not (contains? @status/*failures closed-id)))
         ((:close! failed))
         (done)) 30))))

(deftest different-reply-readers-share-a-bulk-query
  (async done
    (let [mock (mocks/mock-client) before @db/app-db old @scoped/*transport
          reads (atom []) callbacks (atom [])
          opts (fn [parent] {:scoped? true :path-collection [:blog-comments]
                            :where [[:parent-post :== 42] [:parent-comment :== parent]]})]
      (scoped/connect! (:sdk mock) (fn [query success _] (swap! reads conj query) (swap! callbacks conj success)))
      (let [a (scoped/ensure-query! (opts "a")) b (scoped/ensure-query! (opts "b"))]
        @a @b
        (scoped/drain!)
        (is (= 1 (count @reads)))
        (is (= #{"a" "b"} (set (last (last (:where (first @reads)))))))
        ((first @callbacks) {:docs [{:id "a1" :data {:id "a1" :parent-post 42 :parent-comment "a"}}
                                   {:id "b1" :data {:id "b1" :parent-post 42 :parent-comment "b"}}]})
        (-> (mocks/tick!)
            (.then (fn [_]
                     (is (= ["a1"] (mapv :id (:docs @a))))
                     (is (= ["b1"] (mapv :id (:docs @b))))
                     (is (identical? a (scoped/ensure-query! (opts "a"))))))
            (.catch #(is false (str %)))
            (.finally (fn [] (ratom/dispose! a) (ratom/dispose! b)
                        (scoped/connect! (:client old) (:read! old))
                        (reset! db/app-db before) (done))))))))

(deftest profile-readers-use-one-filtered-batch-and-retain-distinct-results
  (async done
    (let [mock (mocks/mock-client) before @db/app-db old @scoped/*transport
          reads (atom []) callback (atom nil)]
      (scoped/connect! (:sdk mock) (fn [query success _] (swap! reads conj query) (reset! callback success)))
      (let [a (scoped/ensure-query! (query/profile-query "a"))
            b (scoped/ensure-query! (query/profile-query "b"))]
        @a @b
        (scoped/drain!)
        (is (= 1 (count @reads)))
        (is (= :in (second (first (:where (first @reads))))))
        (@callback {:docs [{:id "a" :data {:id "a" :name "Alice"}}
                          {:id "b" :data {:id "b" :name "Bob"}}]})
        (-> (mocks/tick!)
            (.then (fn [_]
                     (is (= "Alice" (get-in @a [:docs 0 :data :name])))
                     (is (= "Bob" (get-in @b [:docs 0 :data :name])))
                     (is (identical? a (scoped/ensure-query! (query/profile-query "a"))))))
            (.catch #(is false (str %)))
            (.finally (fn [] (ratom/dispose! a) (ratom/dispose! b)
                        (scoped/connect! (:client old) (:read! old))
                        (reset! db/app-db before) (done))))))))
