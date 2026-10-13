(ns tolgraven.re-frame-upgrade-test
  (:require [clojure.core.rrb-vector :as rrb]
            [clojure.core.rrb-vector.rrbt :as rrbt]
            [cljs.test :refer-macros [deftest is async]]


            [tolgraven.react :as r]



            [tolgraven.component.persistent-state]
            [tolgraven.modules.blog.events]))

(deftest instrumented-component-registrations-retain-source-locations
  (let [handler (get-in @r/kind->id->handler [:event :component-state/update])]
    (is (string? (:file (meta handler))))
    (is (pos-int? (:line (meta handler))))))

(deftest scoped-update-settles-without-a-guessed-event-delay
  (async done
    (let [before @r/*app-db
          path [:state :upgrade-test]
          ;; Keep instrumented macro expansion outside cljs.test/async: its
          ;; expression lifting would await the Promise before .then.
          settle! (fn []
                    (r/dispatch-and-settle [:component-state/update path (fnil + 0) [3]]
                                            {:timeout-ms 2000}))]
      (-> (settle!)
          (.then (fn [result]
                   (is (:ok? result) (pr-str result))
                   (is (= 3 (get-in @r/*app-db path)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (reset! r/*app-db before) (done)))))))

(deftest blog-edit-can-be-inspected-with-dispatch-local-effect-overrides
  (let [*effects (atom []) before @r/*app-db
        post {:id 8 :title "Edit me"}]
    ;; Exercise the real handler without opening an editor or replacing a global
    ;; effect registration. Overrides live on this dispatch and its cascade.
    (r/dispatch-sync-with [:blog/edit-post post]
                           {:dispatch-n #(swap! *effects into %)})
    (is (= [[:form-field [:post-blog] post :blur]
            [:blog/state [:editing] post]
            [:common/navigate! :new-post]] @*effects))
    (is (= before @r/*app-db))))

(deftest rrb-positional-constructor-uses-its-own-vector-type
  (let [^js source (rrb/vec (range 80))
        rebuilt (rrbt/->Vector (.-cnt source) (.-shift source) (.-root source)
                              (.-tail source) nil nil)]
    (is (instance? rrbt/Vector rebuilt))
    (is (= (vec (range 80)) rebuilt))
    (is (= (vec (range 15 70)) (rrb/subvec rebuilt 15 70)))
    (is (= 160 (count (rrb/catvec rebuilt rebuilt))))))

(r/reg-sub :upgrade/safe-read
  (fn [_ _] :ready))

(deftest safe-shim-read-keeps-the-core-error-handler-and-no-reactive-cache
  (let [handler (r/get-handler :error :event-handler)
        query [:upgrade/safe-read]]
    ;; This is intentionally outside a mounted component: safe reads support
    ;; SSR/adapters without retaining a dangling reactive subscription.
    (is (= :ready @(r/sub query)))
    (is (nil? (r/cache-lookup query)))
    (is (identical? handler (r/get-handler :error :event-handler)))))
