(ns website.supabase-boot-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as string]
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
          fail! status/fail!
          failures @status/*failures
          *retry (atom [])
          *initialized (atom 0)
          *consumer (atom nil)
          *stage (atom :setup)
          *trace (atom [])
          record! #(swap! *trace (fn [trace] (vec (take-last 12 (conj trace %)))))]
      ;; Temporary fixture-owned attribution. Preserve native effects and the
      ;; failing silence assertion; never clear foreign queued work to pass it.
      (r/reg-global-interceptor
        (r/->interceptor
          :id :supabase-boot-test/trace
          :after (fn [context]
                   (let [event (get-in context [:coeffects :event])
                         id (first event)]
                     (when (or (#{:supabase/init :supabase/error :supabase/fetch-settings
                                  :supabase/initialized :store/init} id)
                               (and (= :http/get id)
                                    (= "/api/supabase/settings" (:uri (second event)))))
                       (record! (cond-> {:kind :event
                                         :stage @*stage
                                         :id id
                                         :sdk-calls @*initialized
                                         :before (get-in context [:coeffects :db :state :supabase-init])
                                         :proposed (get-in context [:effects :db :state :supabase-init])
                                         :reported? (contains? @status/*failures :supabase-init)}
                                  (#{:supabase/init :supabase/error :supabase/fetch-settings} id)
                                  (assoc :attempt (second event))
                                  (and (= :supabase/error id) (map? (nth event 2 nil)))
                                  (assoc :http-status (:status (nth event 2)))
                                  (= :http/get id)
                                  (assoc :failure-event (vec (take 2 (nth event 3 nil)))))))
                     context))))
      (set! status/fail!
        (fn [id title message retry!]
          (when (= :supabase-init id)
            (record! {:kind :status-failure
                      :stage @*stage
                      :id id
                      :title title
                      :sdk-calls @*initialized
                      :stack (string/join "\n" (take 6 (string/split-lines (or (.-stack (js/Error.)) ""))))}))
          (fail! id title message retry!)))
      (status/recover! :supabase-init)
      (r/reg-fx :dispatch-later #(swap! *retry into (filter (fn [item] (= :supabase/fetch-settings (first (:dispatch item)))) %)))
      (set! client/init! (fn [& _]
                          (swap! *initialized inc)
                          (throw (js/Error. "private SDK detail"))))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (let [{:keys [values] :as consumer}
                  (await! (support/mount-subscriptions!
                            {:init [:get :state :supabase-init]}))]
              (reset! *consumer consumer)
              (reset! *stage :attempt-0)
              (r/dispatch-sync [:supabase/init 0 {:url "https://example.invalid" :anon-key "public"}])
              (await! (support/wait-for! #(and (= 1 @*initialized)
                                               (seq @*retry)
                                               (= :loading (:init (values))))
                                        "First SDK failure did not reach its loading/retry result"))
              (is (= 1 (count @*retry)))
              (is (= {:ms 3000, :dispatch [:supabase/fetch-settings 1]} (first @*retry)))
              (is (= :loading (:init (values))))
              (is (not (contains? @status/*failures :supabase-init))
                  (str "First SDK failure must stay silent; native attribution: " (pr-str @*trace)))
              (reset! *stage :attempt-1)
              (r/dispatch-sync [:supabase/init 1 {:url "https://example.invalid" :anon-key "public"}])
              ;; Service status and a newly mounted one-shot read do not
              ;; acknowledge the consumer's queued SDK failure result together.
              (await! (support/wait-for! #(and (= 2 @*initialized)
                                               (= :failed (:init (values)))
                                               (contains? @status/*failures :supabase-init))
                                        "SDK retry failure did not reach its committed failure/status result"))
              (is (= 2 @*initialized) "Both SDK attempts really failed")
              (is (= 1 (count @*retry)) "No third automatic attempt")
              (is (= :failed (:init (values))))
              (is (= "Supabase could not initialize" (get-in @status/*failures [:supabase-init :title])))
              (is (not (.includes (pr-str @status/*failures) "private SDK detail")))))
          (.catch #(is false (str % " native attribution: " (pr-str @*trace))))
          (.finally (fn []
                      (reset! *stage :cleanup)
                      (try
                        (when-let [consumer @*consumer] ((:unmount! consumer)))
                        (finally
                          (r/clear-global-interceptor :supabase-boot-test/trace)
                          (set! status/fail! fail!)
                          (set! client/init! init!)
                          (reset! status/*failures failures)
                          (restore!)
                          (done)))))))))
