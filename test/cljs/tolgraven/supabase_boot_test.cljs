(ns tolgraven.supabase-boot-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as re-frame]
            [tolgraven.react :as rf]
            [tolgraven.events]
            [tolgraven.service-status :as status]
            [tolgraven.supabase.client :as client]
            [tolgraven.test-support :as support]))

(deftest startup-retries-once-and-releases-queued-content-after-recovery
  (async done
    (let [restore! (re-frame/make-restore-fn)
          init! client/init!
          failures @status/*failures
          *requests (atom [])
          *retry (atom nil)
          *initialized (atom 0)]
      (rf/reg-fx :http-xhrio #(swap! *requests conj %))
      (rf/reg-fx :dispatch-later #(when-let [retry (first (filter (fn [item] (= :supabase/fetch-settings (first (:dispatch item)))) %))]
                                   (reset! *retry retry)))
      (set! client/init! (fn [& _] (swap! *initialized inc)))
      (-> (go-promise
            (rf/dispatch-sync [:init/app-db])
            (rf/dispatch-sync [:store/init])
            (await! (support/settle!))
            (rf/dispatch-sync (conj (:on-failure (first @*requests)) {:status 503}))
            (await! (support/settle!))
            (is (= 3000 (:ms @*retry)))
            (is (= :loading (await! (support/state-at! [:state :supabase-init]))))
            (is (not (contains? @status/*failures :supabase-init)))
            (rf/dispatch-sync (:dispatch @*retry))
            (await! (support/settle!))
            (is (= 2 (count @*requests)))
            (rf/dispatch-sync (conj (:on-success (last @*requests))
                                   {:url "https://example.invalid" :anon-key "public"}))
            (await! (support/settle!))
            (is (= 1 @*initialized))
            (is (= :ready (await! (support/state-at! [:state :supabase-init]))))
            (is (true? (await! (support/state-at! [:state :booted :store])))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! client/init! init!)
                      (reset! status/*failures failures)
                      (restore!) (done)))))))

(deftest sdk-failure-is-silent-first-and-reported-after-the-retry
  (async done
    (let [restore! (re-frame/make-restore-fn)
          init! client/init!
          failures @status/*failures
          *retry (atom [])]
      (rf/reg-fx :dispatch-later #(swap! *retry into (filter (fn [item] (= :supabase/fetch-settings (first (:dispatch item)))) %)))
      (set! client/init! (fn [& _] (throw (js/Error. "private SDK detail"))))
      (-> (go-promise
            (rf/dispatch-sync [:init/app-db])
            (rf/dispatch-sync [:supabase/init 0 {:url "https://example.invalid" :anon-key "public"}])
            (await! (support/settle!))
            (is (= 1 (count @*retry)))
            (is (not (contains? @status/*failures :supabase-init)))
            (rf/dispatch-sync [:supabase/init 1 {:url "https://example.invalid" :anon-key "public"}])
            (await! (support/settle!))
            (is (= 1 (count @*retry)) "No third automatic attempt")
            (is (= :failed (await! (support/state-at! [:state :supabase-init]))))
            (is (= "Supabase could not initialize" (get-in @status/*failures [:supabase-init :title])))
            (is (not (.includes (pr-str @status/*failures) "private SDK detail"))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! client/init! init!)
                      (reset! status/*failures failures)
                      (restore!) (done)))))))
