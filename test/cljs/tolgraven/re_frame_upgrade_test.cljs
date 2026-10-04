(ns tolgraven.re-frame-upgrade-test
  (:require [clojure.core.rrb-vector :as rrb]
            [clojure.core.rrb-vector.rrbt :as rrbt]
            [cljs.test :refer-macros [deftest is async]]
            [re-frame.core-instrumented :as rf]
            [re-frame.tooling :as tooling]
            [re-frame.db :as db]
            [tolgraven.component.persistent-state]
            [tolgraven.blog.events]))

(deftest instrumented-component-registrations-retain-source-locations
  (let [handler (get-in @tooling/kind->id->handler [:event :component-state/update])]
    (is (string? (:file (meta handler))))
    (is (pos-int? (:line (meta handler))))))

(deftest scoped-update-settles-without-a-guessed-event-delay
  (async done
    (let [before @db/app-db
          path [:state :upgrade-test]]
      (-> (rf/dispatch-and-settle [:component-state/update path (fnil + 0) [3]]
                                 {:timeout-ms 2000})
          (.then (fn [result]
                   (is (:ok? result) (pr-str result))
                   (is (= 3 (get-in @db/app-db path)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (reset! db/app-db before) (done)))))))

(deftest blog-edit-can-be-inspected-with-dispatch-local-effect-overrides
  (let [*effects (atom []) before @db/app-db
        post {:id 8 :title "Edit me"}]
    ;; Exercise the real handler without opening an editor or replacing a global
    ;; effect registration. Overrides live on this dispatch and its cascade.
    (rf/dispatch-sync-with [:blog/edit-post post]
                           {:dispatch-n #(swap! *effects into %)})
    (is (= [[:form-field [:post-blog] post :blur]
            [:blog/state [:editing] post]
            [:common/navigate! :new-post]] @*effects))
    (is (= before @db/app-db))))

(deftest rrb-positional-constructor-uses-its-own-vector-type
  (let [^js source (rrb/vec (range 80))
        rebuilt (rrbt/->Vector (.-cnt source) (.-shift source) (.-root source)
                              (.-tail source) nil nil)]
    (is (instance? rrbt/Vector rebuilt))
    (is (= (vec (range 80)) rebuilt))
    (is (= (vec (range 15 70)) (rrb/subvec rebuilt 15 70)))
    (is (= 160 (count (rrb/catvec rebuilt rebuilt))))))
