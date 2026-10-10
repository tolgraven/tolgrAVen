(ns tolgraven.react-hooks-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.test-support :as support]))

(defc <hook-probe> [dependency phase enabled? *observed *lifecycle]
  (let [memo (rf/use-memo #(js-obj) [dependency])
        callback (rf/use-callback (fn [] nil) (list dependency))]
    (rf/use-layout-effect
      (fn []
        (reset! *observed {:memo memo, :callback callback})
        (when enabled?
          (swap! *lifecycle conj [:layout-start dependency])
          #(swap! *lifecycle conj [:layout-stop dependency])))
      [dependency phase enabled?])
    (rf/use-effect
      (fn []
        (when enabled?
          (swap! *lifecycle conj [:effect-start dependency])
          #(swap! *lifecycle conj [:effect-stop dependency])))
      [dependency enabled?])
    [:span (str phase)]))

(deftest clojure-hook-dependencies-preserve-identity-and-effects-clean-up
  (async done
    (let [*observed (atom nil)
          *lifecycle (atom [])
          dependency {:enabled? true}
          equal-dependency (into {} dependency)]
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
                  (await! (support/render! root [<hook-probe> equal-dependency 2 true *observed *lifecycle]))
                  (is (= dependency equal-dependency))
                  (is (not (identical? dependency equal-dependency)))
                  (is (not (identical? memo (:memo @*observed)))
                      "Equal but distinct Clojure map dependencies still have distinct native identities")
                  (is (not (identical? callback (:callback @*observed)))))
                (is (= [[:layout-start equal-dependency] [:effect-start equal-dependency]] @*lifecycle))
                (await! (support/render! root [<hook-probe> dependency 3 true *observed *lifecycle]))
                (is (= 1 (count (filter #(= [:layout-stop equal-dependency] %) @*lifecycle))))
                (is (= 1 (count (filter #(= [:effect-stop equal-dependency] %) @*lifecycle))))
                (finally (support/unmount! root)))
              (await! (support/settle!))
              (is (= 2 (count (filter #(= :layout-stop (first %)) @*lifecycle))))
              (is (= 2 (count (filter #(= :effect-stop (first %)) @*lifecycle))))))
          (.catch #(is false (str %)))
          (.finally done)))))
