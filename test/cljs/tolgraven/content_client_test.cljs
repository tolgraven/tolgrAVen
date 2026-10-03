(ns tolgraven.content-client-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.db :as rfdb]
            [re-frame.core :as rf]
            [ajax.core :as ajax]
            [tolgraven.service-status :as status]
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

(deftest failed-content-is-visible-deduplicated-and-retryable
  (async done
    (let [before @rfdb/app-db failures @status/*failures
          original-get ajax/GET original-dispatch rf/dispatch events (atom [])
          succeed? (atom false)
          rejected! (fn [promise] (-> promise
                                     (.then (fn [_] (is false "Request should fail")))
                                     (.catch (fn [_] nil))))]
      (reset! status/*failures {})
      (reset! content/*pending {})
      (reset! rfdb/app-db {:content {:cv {:title "Keep me"}}})
      (set! rf/dispatch #(swap! events conj %))
      (set! ajax/GET (fn [_ & [options]]
                      (if (= true @succeed?)
                        ((:handler options) {:version 1 :content {:story {:title "Recovered"}}})
                        (if (= :invalid @succeed?)
                          ((:handler options) {:version 999 :content {}})
                          ((:error-handler options) {:status 503})))))
      (-> (rejected! (content/ensure! [:story]))
          (.then (fn []
                   (is (empty? @content/*pending))
                   (is (= "Keep me" (get-in @rfdb/app-db [:content :cv :title])))
                   (is (contains? @status/*failures [:strapi [:story]]))
                   (reset! succeed? :invalid)
                   (rejected! (content/ensure! [:story]))))
          (.then (fn []
                   (is (= 1 (count (filter #(= :diag/new (first %)) @events))))
                   (reset! succeed? true)
                   (content/ensure! [:story])))
          (.then (fn []
                   (is (empty? @status/*failures))
                   (is (= "Recovered" (get-in @rfdb/app-db [:content :story :title])))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! ajax/GET original-get) (set! rf/dispatch original-dispatch)
                      (reset! status/*failures failures) (reset! rfdb/app-db before) (done)))))))
