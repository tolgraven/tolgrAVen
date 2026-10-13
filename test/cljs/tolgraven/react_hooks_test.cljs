(ns tolgraven.react-hooks-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [tolgraven.react :as r]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.test-support :as support]))

(defc <hook-probe> [dependency phase enabled? *observed *lifecycle]
  (let [memo (r/use-memo #(js-obj) [dependency])
        callback (r/use-callback (fn [] nil) (list dependency))]
    (r/use-layout-effect
      (fn [] (reset! *observed {:memo memo, :callback callback})))
    (r/use-layout-effect
      (fn []
        (when enabled?
          (swap! *lifecycle conj [:layout-start dependency])
          #(swap! *lifecycle conj [:layout-stop dependency])))
      [dependency enabled?])
    (r/use-effect
      (fn []
        (when enabled?
          (swap! *lifecycle conj [:effect-start dependency])
          #(swap! *lifecycle conj [:effect-stop dependency])))
      [dependency enabled?])
    [:span (str phase)]))

(deftest equal-clojure-dependencies-retain-ownership-and-changed-values-clean-up
  (async done
    (let [*observed (atom nil)
          *lifecycle (atom [])
          dependency {:enabled? true}
          equal-dependency (into {} dependency)
          changed-dependency {:enabled? false}]
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                ;; Both effects return implicit nil; later commits and unmount
                ;; must not try to call null as a cleanup function.
                (await! (support/render! root [<hook-probe> dependency 0 false *observed *lifecycle]))
                (let [{:keys [memo callback]} @*observed]
                  (is (nil? (callback)) "Callbacks retain their Clojure return values")
                  (await! (support/render! root [<hook-probe> dependency 1 false *observed *lifecycle]))
                  (is (= "1" (.-textContent element)))
                  (is (identical? memo (:memo @*observed)))
                  (is (identical? callback (:callback @*observed)))
                  (is (empty? @*lifecycle))
                  (await! (support/render! root [<hook-probe> equal-dependency 2 false *observed *lifecycle]))
                  (is (= dependency equal-dependency))
                  (is (not (identical? dependency equal-dependency)))
                  (is (identical? memo (:memo @*observed))
                      "Equal Clojure values retain memo and callback identity")
                  (is (identical? callback (:callback @*observed))))
                (is (empty? @*lifecycle))
                (await! (support/render! root [<hook-probe> dependency 3 true *observed *lifecycle]))
                (is (= [[:layout-start equal-dependency] [:effect-start equal-dependency]] @*lifecycle))
                (await! (support/render! root [<hook-probe> equal-dependency 4 true *observed *lifecycle]))
                (is (= 2 (count @*lifecycle)) "Equal values do not disconnect/reconnect resources")
                (let [{:keys [memo callback]} @*observed]
                  (await! (support/render! root [<hook-probe> changed-dependency 5 true *observed *lifecycle]))
                  (is (not (identical? memo (:memo @*observed))))
                  (is (not (identical? callback (:callback @*observed)))))
                (is (= 1 (count (filter #(= [:layout-stop equal-dependency] %) @*lifecycle))))
                (is (= 1 (count (filter #(= [:effect-stop equal-dependency] %) @*lifecycle))))
                (finally (support/unmount! root)))
              (await! (support/settle!))
              (is (= 2 (count (filter #(= :layout-stop (first %)) @*lifecycle))))
              (is (= 2 (count (filter #(= :effect-stop (first %)) @*lifecycle))))))
          (.catch #(is false (str %)))
          (.finally done)))))

(defc <state-probe> [phase *observed]
  (let [[state set-state!] (r/use-state {:value 0})
        [reduced reduce!] (r/use-reducer (fn [state value] (assoc state :value value)) {:value 0})]
    (r/use-layout-effect
      #(reset! *observed {:state state
                         :reduced reduced
                         :set-state! set-state!
                         :reduce! reduce!}))
    [:span (str phase ":" (:value state) ":" (:value reduced))]))

(deftest state-and-reducer-preserve-equal-clojure-values
  (async done
    (let [*observed (atom nil)]
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [<state-probe> 0 *observed]))
                (let [{:keys [state reduced set-state! reduce!]} @*observed]
                  (set-state! assoc :value 0)
                  (reduce! 0)
                  (await! (support/render! root [<state-probe> 1 *observed]))
                  (is (identical? state (:state @*observed)))
                  (is (identical? reduced (:reduced @*observed)))
                  (set-state! assoc :value 1)
                  (reduce! 1)
                  (await! (support/settle!))
                  (is (= "1:1:1" (.-textContent element)))
                  (is (not (identical? state (:state @*observed))))
                  (is (not (identical? reduced (:reduced @*observed)))))
                (finally (support/unmount! root)))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest native-handles-and-functions-retain-identity-semantics-inside-clojure-dependencies
  (async done
    (let [*observed (atom nil)
          *lifecycle (atom [])
          handle (js-obj)
          callback (fn [] nil)
          dependency [handle callback]]
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [<hook-probe> dependency 0 true *observed *lifecycle]))
                (let [memo (:memo @*observed)]
                  (await! (support/render! root [<hook-probe> (vec (seq dependency)) 1 true *observed *lifecycle]))
                  (is (identical? memo (:memo @*observed)))
                  (is (= 2 (count @*lifecycle)))
                  (await! (support/render! root [<hook-probe> [(js-obj) callback] 2 true *observed *lifecycle]))
                  (is (not (identical? memo (:memo @*observed)))))
                (let [memo (:memo @*observed)]
                  (await! (support/render! root [<hook-probe> [handle (fn [] nil)] 3 true *observed *lifecycle]))
                  (is (not (identical? memo (:memo @*observed)))))
                (finally (support/unmount! root)))))
          (.catch #(is false (str %)))
          (.finally done)))))
