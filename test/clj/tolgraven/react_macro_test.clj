(ns tolgraven.react-macro-test
  (:require [cljs.env :as env]
            [clojure.test :refer [deftest is testing]]
            [tolgraven.react]))

(defn- expand [options form]
  (binding [env/*compiler* (atom {:options options})]
    (macroexpand-1 form)))

(def calls
  '[(tolgraven.react/dispatch [:example])
    (tolgraven.react/dispatch-sync [:example])
    (tolgraven.react/subscribe [:example])
    (tolgraven.react/reg-event-db :example identity)
    (tolgraven.react/reg-event-fx :example identity)
    (tolgraven.react/reg-sub :example identity)
    (tolgraven.react/reg-sub-raw :example identity)
    (tolgraven.react/reg-fx :example identity)
    (tolgraven.react/reg-cofx :example identity)])

(deftest production-expands-to-ordinary-api
  (doseq [options [{:closure-defines {'goog.DEBUG false}}
                   {:closure-defines {"goog.DEBUG" false}}
                   {:optimizations :advanced}]
          call calls]
    (let [expanded (expand options call)
          nodes (set (tree-seq coll? seq expanded))]
      (is (some #(and (symbol? %) (= "re-frame.core" (namespace %))) nodes))
      (is (not (contains? nodes :re-frame/source)))
      (is (not (contains? nodes 're-frame.interop/debug-enabled?)))
      (is (not (contains? nodes 're-frame.registrar/-decorate-handler-meta!))))))

(deftest development-retains-real-caller-metadata
  (doseq [call calls]
    (let [call' (with-meta call {:file "example.cljs", :line 42})
          nodes (tree-seq coll? seq (expand {:closure-defines {'goog.DEBUG true}} call'))]
      (is (some #{'re-frame.interop/debug-enabled?} nodes))
      (is (some #(= {:file "example.cljs", :line 42} %) nodes)))))

(deftest production-retains-public-contract-wrappers
  (let [nodes (set (tree-seq coll? seq
                           (expand {:closure-defines {'goog.DEBUG false}}
                                   '(tolgraven.react/reg-event-db :example
                                      {:args [:tuple :string]} identity))))]
    (is (contains? nodes 'tolgraven.validation.bindings/register!))
    (is (contains? nodes 'tolgraven.validation.bindings/event-interceptor))
    (is (contains? nodes 're-frame.core/reg-event-db))))
