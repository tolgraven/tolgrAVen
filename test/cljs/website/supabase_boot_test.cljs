(ns website.supabase-boot-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]

            [website.events]
            [website.service-status :as status]
            [website.modules.supabase.client :as client]
            [website.test-support :as support]))

(deftest startup-retries-once-and-releases-queued-content-after-recovery
  (async done
    (let [restore! (r/make-restore-fn)
          init! client/init!
          failures @status/*failures
          *requests (atom [])
          *retry (atom nil)
          *initialized (atom 0)
          *consumer (atom nil)]
      (r/reg-fx :http-xhrio #(swap! *requests conj %))
      (status/recover! :supabase-init)
      (r/reg-fx :dispatch-later #(when-let [retry (first (filter (fn [item] (= :supabase/fetch-settings (first (:dispatch item)))) %))]
                                   (reset! *retry retry)))
      (set! client/init! (fn [& _] (swap! *initialized inc)))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (let [{:keys [values] :as consumer}
                  (await! (support/mount-subscriptions!
                            {:init [:get :state :supabase-init]
                             :booted [:get :state :booted :store]}))]
              (reset! *consumer consumer)
              (r/dispatch-sync [:store/init])
              (await! (support/wait-for! #(and (seq @*requests) (= :loading (:init (values))))
                                        "Store startup did not issue its settings request"))
              (is (= 1 (count @*requests)))
              (r/dispatch-sync (conj (:on-failure (first @*requests)) {:status 503}))
              (await! (support/wait-for! #(some? @*retry)
                                        "First startup failure did not schedule its retry"))
              (is (= 3000 (:ms @*retry)))
              (is (= :loading (:init (values))))
              (is (not (contains? @status/*failures :supabase-init)))
              (r/dispatch-sync (:dispatch @*retry))
              (await! (support/wait-for! #(<= 2 (count @*requests))
                                        "The scheduled retry did not issue its settings request"))
              (is (= 2 (count @*requests)))
              (r/dispatch-sync (conj (:on-success (last @*requests))
                                     {:url "https://example.invalid" :anon-key "public"}))
              ;; Render ticks cannot acknowledge this Promise/result-event chain.
              ;; Observe the same native mounted subscriptions a consumer uses.
              (await! (support/wait-for! #(and (= :ready (:init (values))) (true? (:booted (values))))
                                        "Recovered initialization did not reach its ready/booted result"))
              (is (= 1 @*initialized))
              (is (= :ready (:init (values))))
              (is (true? (:booted (values))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when-let [consumer @*consumer] ((:unmount! consumer)))
                      (set! client/init! init!)
                      (reset! status/*failures failures)
                      (restore!)
                      (done)))))))

(deftest sdk-failure-is-silent-first-and-reported-after-the-retry
  (async done
    (let [restore! (r/make-restore-fn)
          init! client/init!
          failures @status/*failures
          *retry (atom [])]
      (status/recover! :supabase-init)
      (r/reg-fx :dispatch-later #(swap! *retry into (filter (fn [item] (= :supabase/fetch-settings (first (:dispatch item)))) %)))
      (set! client/init! (fn [& _] (throw (js/Error. "private SDK detail"))))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:supabase/init 0 {:url "https://example.invalid" :anon-key "public"}])
            (await! (support/settle!))
            (is (= 1 (count @*retry)))
            (is (not (contains? @status/*failures :supabase-init)))
            (r/dispatch-sync [:supabase/init 1 {:url "https://example.invalid" :anon-key "public"}])
            (await! (support/settle!))
            (await! (support/wait-for! #(contains? @status/*failures :supabase-init)
                                      "SDK retry failure did not reach its result event"))
            (is (= 1 (count @*retry)) "No third automatic attempt")
            (is (= :failed (await! (support/state-at! [:state :supabase-init]))))
            (is (= "Supabase could not initialize" (get-in @status/*failures [:supabase-init :title])))
            (is (not (.includes (pr-str @status/*failures) "private SDK detail"))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! client/init! init!)
                      (reset! status/*failures failures)
                      (restore!) (done)))))))
