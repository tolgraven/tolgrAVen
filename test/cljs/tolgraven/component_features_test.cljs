(ns tolgraven.component-features-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [tolgraven.component.features :as features]))

(deftest instance-options-preserve-order-and-intent
  (let [defaults [:props [:appear {:class "opacity", :once? true}]
                  [:seen {:threshold 0.5}]]]
    (testing "missing options inherit, maps refine, and new features append"
      (is (= [[:props true]
              [:appear {:class "slide-in", :once? true}]
              [:seen {:threshold 0.5}]
              [:on-seen {:once? true}]]
             (features/resolve-options
               defaults
               {:features [[:appear {:class "slide-in"}]
                           [:on-seen {:once? true}]]} []))))
    (testing "false and nil disable while scalars replace"
      (is (= [[:appear "zoom"]]
             (features/resolve-options defaults
                                       {:features [[:props false]
                                                   [:appear "zoom"]
                                                   [:seen nil]]} []))))
    (testing "true inherits enabled configuration or enables a disabled default"
      (is (= [[:appear {:class "opacity", :once? true}] [:exit true]]
             (features/resolve-options [[:appear {:class "opacity", :once? true}]
                                        [:exit false]]
                                       {:features [:appear :exit]} []))))
    (testing "duplicates retain their first position and last supplied value"
      (is (= [[:props true] [:appear "last"] [:seen {:threshold 0.5}]
              [:font {:family "Ready"}]]
             (features/resolve-options defaults
                                       {:features [[:appear "first"]
                                                   [:font {:family "Ready"}]
                                                   [:appear "last"]]} []))))))

(deftest nested-overrides-win-without-promoting-domain-keys
  (is (= [[:props true] [:appear {:class "nested", :once? true}]]
         (features/resolve-options [:props [:appear {:class "default", :once? true}]]
                                   {:appear false
                                    :seen {:threshold 0.25}
                                    :features [[:appear {:class "nested"}]]} [])))
  (is (= [[:props true]]
         (features/resolve-options [:props [:appear "default"]]
                                   {:appear "legacy", :features [[:appear false]]} [])))
  (is (= [[:appear "legacy"]]
         (features/resolve-options [[:appear "default"]] {:appear "legacy"} []))))

(deftest disabled-options-never-evaluate-default-functions
  (let [*calls (atom [])
        defaults [[:seen (fn [spec]
                           (swap! *calls conj (:marker spec))
                           {:threshold 0.5, :once? true})]]]
    (doseq [disabled [false nil]]
      (is (= [] (features/resolve-options defaults
                                          {:features [[:seen disabled]]}
                                          [{:marker :disabled}]))))
    (is (empty? @*calls))
    (is (= [[:seen {:threshold 0.25, :once? true}]]
           (features/resolve-options defaults
                                     {:features [[:seen {:threshold 0.25}]]}
                                     [{:marker :merged}])))
    (is (= [:merged] @*calls))
    (is (= [[:seen {:threshold 0.5, :once? true}]]
           (features/resolve-options defaults {:features [:seen]}
                                     [{:marker :inherited}])))
    (is (= [:merged :inherited] @*calls))))

(deftest legacy-template-choice-retains-declared-attrs
  (let [*calls (atom 0)
        defaults [[:container (fn [_]
                                (swap! *calls inc)
                                {:tag :section, :props {:class "layout"}})]]]
    (is (= [[:container {:tag :section
                        :props {:class "layout"}
                        :form [:article {:class "template"} :container/content]}]]
           (features/resolve-options defaults
                                     {:container [:article {:class "template"}
                                                  :container/content]}
                                     [{}])))
    (is (= 1 @*calls))))
