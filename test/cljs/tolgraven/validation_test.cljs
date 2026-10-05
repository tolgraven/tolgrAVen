(ns tolgraven.validation-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as re-frame]
            [reitit.frontend :as frontend]
            [tolgraven.react :as rf]
            [tolgraven.routes :as routes]
            [tolgraven.component :as component]
            [tolgraven.component.registry :as registry]
            [tolgraven.schema.declarations :as schemas]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.test-support :as support]
            [tolgraven.validation.runtime :as validation]
            [tolgraven.validation.views :as views]))

(defonce *effects (atom 0))
(rf/reg-fx :validation-test/external (fn [_] (swap! *effects inc)))
(rf/reg-event-fx :validation-test/invalid
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:options :blog :posts-per-page] -5)
     :validation-test/external true}))

(deftest frontend-uses-the-same-route-coercion
  (let [match (frontend/match-by-path routes/router "/blog/page/2?userBox=false")]
    (is (= 2 (get-in match [:parameters :path :nr])))
    (is (false? (get-in match [:parameters :query :userBox]))))
  (is (thrown? js/Error (frontend/match-by-path routes/router "/blog/page/zero"))))

(deftest event-validation-checks-db-presence-and-honors-disable
  (let [enabled? @validation/*enabled?
        context {:coeffects {:db {} :event [:test/reset]}
                 :effects {:db nil :test/external true}}]
    (try
      (reset! validation/*enabled? true)
      (is (= [:validation/report {:contract :app-db :event :test/reset
                                 :issues [{:path [] :message "invalid type"}]}]
             (get-in (validation/intercept context) [:effects :dispatch])))
      (is (= #{:dispatch} (set (keys (:effects (validation/intercept context))))))
      (reset! validation/*enabled? false)
      (is (= context (validation/intercept context)))
      (finally (reset! validation/*enabled? enabled?)))))

(deftest invalid-transaction-preserves-mounted-state-and-reports-the-path
  (async done
    (-> (go-promise
          (let [restore! (re-frame/make-restore-fn)
                enabled? @validation/*enabled?
                element (.createElement js/document "div")
                root (await! (support/create-root! element))]
            (.appendChild (.-body js/document) element)
            (try
              (reset! validation/*enabled? true)
              (rf/reg-global-interceptor (rf/->interceptor :id :validation/app-db :after validation/intercept))
              (rf/dispatch [:init/app-db])
              (await! (support/render! root [views/<reports>]))
              (reset! *effects 0)
              (rf/dispatch [:validation-test/invalid])
              (await! (support/settle!))
              (is (= 3 (await! (support/state-at! [:options :blog :posts-per-page]))))
              (is (zero? @*effects))
              (is (.includes (.-textContent element) "[:options :blog :posts-per-page]"))
              (is (not (.includes (.-textContent element) "-5")))
              (rf/dispatch [:validation-test/invalid])
              (await! (support/settle!))
              (is (.includes (.-textContent element) "× 2"))
              (finally
                (support/unmount! root) (.remove element)
                (rf/clear-global-interceptor :validation/app-db)
                (reset! validation/*enabled? enabled?)
                (restore!)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))


(defc <positional>
  {:schema [:map [:category :keyword]]
   :category :display
   :args-schema [:tuple [:map [:title :string]] :int]}
  [{:keys [title]} count]
  [:p title ": " count])

(defc <typed-spec>
  {:features [:error-boundary :props]
   :spec-schema (schemas/extend-spec [:map [:label :string]])}
  [{:keys [label] :as spec}]
  [:p label])

(defc <variadic>
  {:args-schema [:cat :string [:* :int]]}
  [label & amounts]
  [:p label ": " (reduce + 0 amounts)])

(defc <multi-arity>
  {:args-schema [:alt [:cat :int] [:cat :int :int]]}
  ([a] (<multi-arity> a a))
  ([a b] [:p (+ a b)]))

(deftest component-input-schemas-cover-positional-destructured-variadic-and-spec-inputs
  (is (= :display (get-in (registry/component-spec <positional>) [:options :category])))
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [:<> [<positional> {:title "Count"} 3]
                                             [<variadic> "Sum" 1 2 3]
                                             [<multi-arity> 4]
                                             [<typed-spec> {:label "Spec" :props {:title "Native attribute"}}]]))
              (is (.includes (.-textContent element) "Count: 3"))
              (is (.includes (.-textContent element) "Sum: 6"))
              (is (.includes (.-textContent element) "8"))
              (is (some? (.querySelector element "[title='Native attribute']")))
              (await! (support/render! root
                        [component/<boundary> {:ns-name "test" :component-name "positional"}
                         [<positional> {:title "Count"} "private-invalid-value"]]))
              (is (.includes (.-textContent element) "should be an integer"))
              (is (not (.includes (.-textContent element) "private-invalid-value")))
              (await! (support/render! root [<typed-spec> {:label "Spec" :props "invalid"}]))
              (is (some? (.querySelector element "[role=alert]")))
              (is (.includes (.-textContent element) "[:props]"))
              (finally (support/unmount! root) (.remove element)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
