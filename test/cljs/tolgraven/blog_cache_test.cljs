(ns tolgraven.blog-cache-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as re-frame]
            [tolgraven.react :as rf]
            [tolgraven.modules.blog.cache :as cache]
            [reagent.core :as r]
            [tolgraven.modules.blog.events]
            [tolgraven.component.storage :as storage]
            [tolgraven.component.restore :as restore]
            [tolgraven.render-context :as context]
            [tolgraven.browser-resources :as resources]
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

(deftest network-ssr-restores-only-after-page-gate-and-keeps-interactions
  (async done
    (let [restore-db! (re-frame/make-restore-fn)
          old-context @restore/*context
          old-snapshot @context/*snapshot
          ready! storage/ready!
          read! storage/read!
          after-page! resources/after-page!
          start! cache/start!
          *gate (atom nil)
          *starts (atom 0)
          *cancelled (atom 0)
          path [:state :blog :comment-thread-expanded]
          thread [24 "105"]
          untouched [24 "106"]]
      (rf/reg-fx :blog/cache-state (fn [_]))
      (rf/reg-fx :component-storage/commit (fn [_]))
      (set! storage/ready! (fn ([] (js/Promise.resolve nil))
                                  ([_] (js/Promise.resolve nil))))
      (set! storage/read! (fn [id _]
                           (when (= id [:state path])
                             {:value {thread true untouched true}})))
      (set! resources/after-page! (fn [callback]
                                   (reset! *gate callback)
                                   #(swap! *cancelled inc)))
      (set! cache/start! #(swap! *starts inc))
      (rf/dispatch-sync [:init/app-db])
      (reset! restore/*context {:hydrate? true})
      (reset! context/*snapshot {:route-parameters {}})
      (is (nil? (cache/install!)) "SSR code installation never waits on its own hydration")
      (-> (js/Promise.resolve nil)
          (.then (fn [_]
                   (is (identical? storage/missing (storage/state-value path)) "Resolved disk read cannot change unhydrated SSR")
                   (is (zero? @*starts) "Do not persist server defaults over saved choices")
                   (rf/dispatch-sync [:blog/expand-comment-thread thread false])
                   (restore/hydrated!)
                   (@*gate)))
          (.then (fn [_]
                   (is (false? (get (storage/state-value path) thread)) "New interaction wins")
                   (is (true? (get (storage/state-value path) untouched)) "Other saved choices restore")
                   (is (= 1 @*starts) "Tracking starts after restoration")
                   (cache/stop!)
                   (is (= 1 @*cancelled))
                   (reset! restore/*context {:hydrate? true})
                   (cache/install!)
                   (cache/stop!)
                   (@*gate)))
          (.then (fn [_]
                   (is (= 1 @*starts) "Stopping cancels even an already-resolved storage continuation")
                   ;; A client-only mount still waits for cache installation.
                   (reset! restore/*context {:hydrate? false})
                   (cache/install!)))
          (.then (fn [_] (is (= 2 @*starts) "Client-only installation completes before mounting")))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (cache/stop!)
                      (set! storage/ready! ready!)
                      (set! storage/read! read!)
                      (set! resources/after-page! after-page!)
                      (set! cache/start! start!)
                      (reset! restore/*context old-context)
                      (reset! context/*snapshot old-snapshot)
                      (restore-db!)
                      (done)))))))
