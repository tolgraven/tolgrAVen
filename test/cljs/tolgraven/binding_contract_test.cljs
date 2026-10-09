(ns tolgraven.binding-contract-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as core]
            [reagent.ratom :as ratom]
            [reagent.core :as reagent]
            [tolgraven.react :as rf]
            [tolgraven.test-support :as support]
            [tolgraven.component :as component]
            [tolgraven.validation.bindings :as contracts]
            [tolgraven.validation.runtime]
            [tolgraven.events]))

(defonce *effects (atom 0))
(defonce *created (atom 0))
(defonce *disposed (atom 0))
(rf/reg-fx :binding-test/effect (fn [_] (swap! *effects inc)))
(rf/reg-event-db :binding-test/set
  {:args [:tuple :int], :coerce :string}
  (fn [db [_ value]] (assoc db :binding-test value)))
(rf/reg-event-fx :binding-test/write
  {:args [:tuple :int]}
  (fn [{:keys [db]} [_ value]]
    {:db (assoc db :binding-test value), :binding-test/effect true}))
(rf/reg-sub :binding-test/value
  {:args [:tuple], :result [:maybe :int]}
  (fn [db _] (:binding-test db)))
(rf/reg-sub :binding-test/echo
  {:args [:tuple :int], :coerce :string, :result :int}
  (fn [_ _] (swap! *created inc) (rf/subscribe [:binding-test/value]))
  (fn [_ [_ number]] number))
(rf/reg-sub :binding-test/invalid
  {:args [:tuple], :result :int}
  (fn [_ _] "private-invalid-result"))
(rf/reg-sub :binding-test/warning
  {:args [:tuple], :result :int, :on-error :warn}
  (fn [_ _] "private-invalid-result"))
(rf/reg-sub-raw :binding-test/source
  {:args [:tuple], :result [:maybe :int]}
  (fn [_ _]
    (ratom/make-reaction #(deref (rf/subscribe [:binding-test/value]))
                        :on-dispose #(swap! *disposed inc))))

(rf/reg-sub-raw :binding-test/dynamic-source
  {:args [:tuple], :result :int}
  (fn [_ _ [value]]
    (ratom/make-reaction (fn [] value))))

(deftest raw-contract-preserves-dynamic-subscription-values
  (async done
    (-> (go-promise
          (let [*value (reagent/atom 7)
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                <value> (fn [] [:span @(rf/subscribe [:binding-test/dynamic-source] [*value])])]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [<value>]))
              (is (= "7" (.-textContent element)))
              (reset! *value 12)
              (await! (support/settle!))
              (is (= "12" (.-textContent element)))
              (finally (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest coerced-event-input-and-invalid-effects-use-real-event-queue
  (async done
    (-> (go-promise
          (let [restore! (core/make-restore-fn)
                enabled? @contracts/*enabled?
                consumer (await! (support/mount-subscriptions!
                                   {:value [:binding-test/value], :errors [:validation/errors]}))]
            (try
              (reset! contracts/*enabled? true)
              (reset! *effects 0)
              (rf/dispatch [:binding-test/set "42"])
              (await! (support/settle!))
              (is (= 42 (:value ((:values consumer)))))
              (rf/dispatch [:binding-test/write "private-invalid-argument"])
              (await! (support/settle!))
              (is (= 42 (:value ((:values consumer)))))
              (is (zero? @*effects))
              (is (some #(= "event-arguments :binding-test/write" (:contract %))
                        (:errors ((:values consumer)))))
              (is (not (.includes (pr-str (:errors ((:values consumer)))) "private-invalid-argument")))
              (reset! contracts/*enabled? false)
              (rf/dispatch [:binding-test/set "43"])
              (await! (support/settle!))
              (is (= 43 (:value ((:values consumer)))) "Explicit coercion is consistent in production")
              (finally ((:unmount! consumer)) (reset! contracts/*enabled? enabled?) (restore!)))))
        (.catch #(is false (str %))) (.finally done))))

(deftest normalized-queries-share-a-mounted-subscription
  (async done
    (-> (go-promise
          (reset! *created 0)
          (let [consumer (await! (support/mount-subscriptions!
                                   {:string [:binding-test/echo "7"]
                                    :number [:binding-test/echo 7]}))]
            (try
              (is (= {:string 7, :number 7} ((:values consumer))))
              (is (= 1 @*created))
              (finally ((:unmount! consumer))))))
        (.catch #(is false (str %))) (.finally done))))

(deftest replacing-a-contracted-subscription-removes-argument-normalization
  (async done
    (-> (go-promise
          (rf/reg-sub :binding-test/replaced
            {:args [:tuple :int], :coerce :string, :result :int}
            (fn [_ [_ value]] value))
          (is (= 7 (await! (support/subscription-value! [:binding-test/replaced "7"]))))
          (rf/reg-sub :binding-test/replaced
            (fn [_ [_ value]] value))
          (is (= "8" (await! (support/subscription-value! [:binding-test/replaced "8"]))))
          (is (= "plain" (await! (support/subscription-value! [:binding-test/replaced "plain"])))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest raw-contract-releases-source-with-last-consumer
  (async done
    (-> (go-promise
          (reset! *disposed 0)
          (let [a (await! (support/mount-subscriptions! {:value [:binding-test/source]}))
                b (await! (support/mount-subscriptions! {:value [:binding-test/source]}))]
            (try
              ((:unmount! a))
              (await! (support/settle!))
              (is (zero? @*disposed) "The remaining consumer retains the source")
              (finally ((:unmount! a)) ((:unmount! b)))))
          (await! (support/settle!))
          (is (= 1 @*disposed) "The final unmount releases the managed reaction"))
        (.catch #(is false (str %))) (.finally done))))

(deftest invalid-subscription-result-reaches-the-component-boundary
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                <value> (fn [] [:span @(rf/subscribe [:binding-test/invalid])])]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root
                        [component/<boundary> {:ns-name "test", :component-name "contract-result"}
                         [<value>]]))
              (is (some? (.querySelector element "[role=alert]")))
              (is (not (.includes (.-textContent element) "private-invalid-result")))
              (finally (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %))) (.finally done))))

(rf/reg-sub :binding-test/coerced-result
  {:args [:tuple], :result :int, :result-coerce :string}
  (fn [_ _] "12"))

(deftest result-coercion-is-consistent-when-validation-is-disabled
  (async done
    (-> (go-promise
          (let [enabled? @contracts/*enabled?]
            (try
              (doseq [enabled? [true false]]
                (reset! contracts/*enabled? enabled?)
                (is (= 12 (await! (support/subscription-value! [:binding-test/coerced-result])))))
              (finally (reset! contracts/*enabled? enabled?)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest warning-results-are-visible-without-a-diagnostic-update-loop
  (async done
    (-> (go-promise
          (let [restore! (core/make-restore-fn)
                consumer (await! (support/mount-subscriptions!
                                   {:value [:binding-test/warning], :errors [:validation/errors]}))]
            (try
              (await! (support/settle!))
              (is (= "private-invalid-result" (:value ((:values consumer)))))
              (let [reports (filter #(= "subscription-result :binding-test/warning" (:contract %))
                                    (:errors ((:values consumer))))]
                (is (= 1 (count reports)))
                (is (= :warn (:severity (first reports))))
                (is (= 1 (:count (first reports))))
                (is (not (.includes (pr-str reports) "private-invalid-result"))))
              (rf/dispatch [:binding-test/set 3])
              (await! (support/settle!))
              (is (= 1 (:count (first (filter #(= "subscription-result :binding-test/warning" (:contract %))
                                             (:errors ((:values consumer))))))))
              (finally ((:unmount! consumer)) (restore!)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest invalid-query-is-rejected-before-source-acquisition
  (async done
    (-> (go-promise
          (reset! *created 0)
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                <value> (fn [] [:span @(rf/subscribe [:binding-test/echo "invalid-private-id"])])]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root
                        [component/<boundary> {:ns-name "test", :component-name "contract-query"}
                         [<value>]]))
              (is (some? (.querySelector element "[role=alert]")))
              (is (zero? @*created))
              (is (not (.includes (.-textContent element) "invalid-private-id")))
              (finally (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %)))
        (.finally done))))
