(ns tolgraven.content-client-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.db :as rfdb]
            [tolgraven.content.client :as content]
            [tolgraven.loader :as loader]
            [shadow.lazy :as lazy]))

(deftest concurrent-module-content-requests-share-a-fetch
  (async done
    (let [before @rfdb/app-db calls (atom []) resolve! (atom nil)
          pending (js/Promise. (fn [resolve _] (reset! resolve! resolve)))]
      (reset! content/*pending {})
      (reset! rfdb/app-db {:content {:document {:title "Loaded"}}})
      (with-redefs [content/request! (fn [ks] (swap! calls conj ks) pending)]
        (let [a (content/ensure! [:document :cv]) b (content/ensure! [:cv])]
          (is (= [[:cv]] @calls))
          (@resolve! nil)
          (-> (js/Promise.all #js [a b])
              (.then (fn [] (is (empty? @content/*pending))))
              (.catch (fn [error] (is false (str error))))
              (.finally (fn [] (reset! rfdb/app-db before) (done)))))))))

(deftest module-init-waits-for-required-content
  (async done
    (let [id (keyword (str (random-uuid))) initialized (atom false) resolve! (atom nil)
          pending (js/Promise. (fn [resolve _] (reset! resolve! resolve)))
          spec {:content [:cv] :init #(reset! initialized true)}
          loadable (reify lazy/ILoadable (ready? [_] true) IDeref (-deref [_] spec))
          original content/ensure!]
      ;; Keep the replacement active across the loader's asynchronous chain.
      (set! content/ensure! (fn [ks] (is (= [:cv] ks)) pending))
      (with-redefs [loader/modules {id loadable}]
        (let [loaded (loader/load! {:module id})]
          (-> (js/Promise.resolve)
              (.then (fn [] (is (false? @initialized)) (@resolve! nil) loaded))
              (.then (fn [_] (is (true? @initialized))))
              (.catch (fn [error] (is false (str error))))
              (.finally (fn [] (set! content/ensure! original) (swap! loader/*loads dissoc id) (done)))))))))

(deftest bootstrap-rejects-content-outside-the-public-contract
  (is (false? (content/valid-bundle? {:version 1 :content {:secrets {}}} []))))
