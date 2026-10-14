(ns website.react-macro-test
  (:require [cljs.env :as env]
            [clojure.test :refer [deftest is testing]]
            [website.react]
            [website.react.targets :as targets]))

(defn- expand [options form]
  (binding [env/*compiler* (atom {:options options})]
    (macroexpand-1 form)))

(def calls
  '[(website.react/dispatch [:example])
    (website.react/dispatch-sync [:example])
    (website.react/subscribe [:example])
    (website.react/reg-event-db :example identity)
    (website.react/reg-event-fx :example identity)
    (website.react/reg-sub :example identity)
    (website.react/reg-sub-raw :example identity)
    (website.react/reg-fx :example identity)
    (website.react/reg-cofx :example identity)])

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
                                   '(website.react/reg-event-db :example
                                      {:args [:tuple :string]} identity))))]
    (is (contains? nodes 'website.validation.bindings/register!))
    (is (contains? nodes 'website.validation.bindings/event-interceptor))
    (is (contains? nodes 're-frame.core/reg-event-db))))

(deftest target-adapter-is-loadable-and-inert-on-the-jvm
  (is (nil? targets/render-to-string))
  (is (nil? targets/render-to-static-markup))
  (is (nil? targets/kind->id->handler))
  (is (nil? targets/query->reaction)))
