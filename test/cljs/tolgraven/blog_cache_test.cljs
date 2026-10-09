(ns tolgraven.blog-cache-test
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.core :as re-frame]
            [tolgraven.react :as rf]
            [tolgraven.modules.blog.cache :as cache]
            [reagent.core :as r]
            [tolgraven.modules.blog.events]
            [tolgraven.component.storage :as storage]
            [tolgraven.supabase.scoped :as scoped]
            [tolgraven.modules.blog.data :as data]))

(deftest disk-restores-display-choices-without-replacing-server-data-or-new-intent
  (let [restore! (re-frame/make-restore-fn)
        path [:state :blog :comment-thread-expanded]
        thread [24 "111" "112" "113"]
        other [24 "105"]
        query-path [:store :scoped (scoped/query-key (data/post-query 24))]
        server {:docs [{:id 24 :data {:id 24 :title "Server"}}]}]
    (rf/reg-fx :blog/cache-state (fn [_]))
    (rf/reg-fx :component-storage/commit (fn [_]))
    (try
      (rf/dispatch-sync [:init/app-db])
      (rf/dispatch-sync [:component-state/reset path {thread false}])
      (rf/dispatch-sync [:store/scoped (last query-path) server])
      (rf/dispatch-sync [:blog/restore-cache [[path {thread true}]
                                             [query-path {:docs []}]] true])
      (is (true? (get (storage/state-value path) thread)) "Saved UI overrides server defaults")
      (is (= server (storage/state-value query-path)) "Server content wins")
      ;; Returning to the server default still counts as an explicit new choice.
      (rf/dispatch-sync [:blog/expand-comment-thread thread false])
      (rf/dispatch-sync [:blog/restore-cache [[path {thread true other true}]] true])
      (is (false? (get (storage/state-value path) thread)))
      (is (true? (get (storage/state-value path) other)))
      (rf/dispatch-sync [:blog/restore-cache [[path {other false}]] false])
      (is (true? (get (storage/state-value path) other)) "An exact local pair owns all display state")
      (finally (restore!)))))

(deftest malformed-disk-display-values-do-not-block-other-restored-choices
  (let [restore! (re-frame/make-restore-fn)
        path [:state :blog :comment-thread-expanded]
        thread [24 "105"]]
    (try
      (rf/dispatch-sync [:init/app-db])
      (doseq [value [nil false 7 "broken" [true]]]
        (rf/dispatch-sync [:blog/restore-cache
                           [[[:state :blog :comments-expanded] value]
                            [path {thread true}]] true])
        (is (true? (get (storage/state-value path) thread))))
      (finally (restore!)))))

(deftest tracking-parses-query-keys-only-when-query-content-changes
  (let [restore! (re-frame/make-restore-fn)
        query-key (scoped/query-key (data/post-query 24))
        parse-query? cache/public-query?
        *parses (atom 0)]
    (with-redefs [cache/public-query? (fn [key] (swap! *parses inc) (parse-query? key))
                  storage/*tracked (atom {})
                  storage/schedule! (fn [])]
      (try
        (rf/dispatch-sync [:init/app-db])
        (rf/dispatch-sync [:store/scoped query-key {:docs []}])
        (cache/start!)
        (r/flush)
        (is (pos? @*parses) "Already seeded SSR queries are tracked immediately")
        (let [initial @*parses]
          (rf/dispatch-sync [:state [:scroll :past-top] true])
          (r/flush)
          (is (= initial @*parses) "Scroll events do not parse persisted query keys")
          (rf/dispatch-sync [:component-state/reset [:state :blog :comments-expanded] {24 true}])
          (r/flush)
          (is (= initial @*parses) "Display changes also retain the parsed queries")
          (rf/dispatch-sync [:store/scoped query-key {:docs [{:id 24}]}])
          (r/flush)
          (is (> @*parses initial) "New query data refreshes the tracked snapshot"))
        (finally (cache/stop!) (restore!))))))
