(ns tolgraven.content-client-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.db :as rfdb]
            [re-frame.core :as rf]
            [ajax.core :as ajax]
            [tolgraven.service-status :as status]
            [tolgraven.content.client :as content]
            [tolgraven.loader :as loader]
            [tolgraven.component.data :as data]
            [tolgraven.content.contract :as contract]
            [tolgraven.component.storage :as storage]
            [tolgraven.component.restore :as restore]
            [shadow.lazy :as lazy]))

(deftest concurrent-module-content-requests-share-a-fetch
  (async done
    (let [before @rfdb/app-db calls (atom []) original content/request!]
      (reset! content/*pending {})
      (reset! rfdb/app-db {:content {:document {:title "Loaded"}}})
      (set! content/request! (fn [ks] (swap! calls conj ks) (js/Promise.resolve nil)))
      (let [a (content/ensure! [:document :cv]) b (content/ensure! [:cv :blog])]
        (is (empty? @calls) "No work until the next tick")
        (-> (js/Promise.all #js [a b])
            (.then (fn [_]
                     (is (= [[:blog :cv]] @calls) "Disjoint sections batch; duplicates disappear")
                     (is (empty? @content/*pending))))
            (.catch #(is false (str %)))
            (.finally (fn [] (set! content/request! original) (reset! rfdb/app-db before) (done))))))))

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
  (is (false? (content/valid-bundle? {:version 1 :content {:secrets {}}} [])))
  (is (false? (content/valid-bundle? {:version 1 :content {:story nil}} [:story])))
  (is (false? (content/valid-bundle? {:version 1 :content {:cv {:cv {:timeline 123}}}} [:cv]))))

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

(deftest module-data-starts-with-code-and-can-retry
  (async done
    (let [id (keyword (str (random-uuid))) *events (atom []) *code (atom nil)
          *attempt (atom 0) resource {:source :module-test}
          original-modules loader/modules original-manifest contract/module-dependencies
          original-load lazy/load
          spec {:init #(swap! *events conj :init)}
          loadable (reify lazy/ILoadable (ready? [_] false) IDeref (-deref [_] spec))]
      (data/register-source! :module-test
                            {:load! (fn [_]
                                      (swap! *events conj :data)
                                      (if (= 1 (swap! *attempt inc))
                                        (js/Promise.reject (js/Error. "First load fails"))
                                        (js/Promise.resolve :loaded)))})
      (set! loader/modules {id loadable})
      (set! contract/module-dependencies {id [resource]})
      (set! lazy/load (fn
                       ([_]
                        (swap! *events conj :code)
                        (js/Promise. (fn [resolve _] (reset! *code resolve))))
                       ([_ _] nil)))
      (let [first-load (loader/load! {:module id})]
        (is (= [:code] @*events) "Code starts while data joins the next batch")
        (@*code spec)
        (-> first-load
            (.then (fn [_] (is false "First module load should reject")))
            (.catch (fn [_]
                      (is (not-any? #{:init} @*events))
                      (let [retried (loader/load! {:module id})]
                        (@*code spec)
                        retried)))
            (.then (fn [_]
                     (is (= 2 @*attempt))
                     (is (= :init (last @*events)))))
            (.catch #(is false (str %)))
            (.finally (fn []
                        (set! loader/modules original-modules)
                        (set! contract/module-dependencies original-manifest)
                        (set! lazy/load original-load)
                        (swap! loader/*loads dissoc id)
                        (data/invalidate! #{resource})
                        (swap! data/*sources dissoc :module-test)
                        (done))))))))

(deftest external-back-restores-public-content-before-network-loads
  (async done
    (let [before @rfdb/app-db context @restore/*context tracked @storage/*tracked
          original content/request! *calls (atom 0)
          bundle {:version contract/version
                  :content (into {} (map (fn [key] [key (if (#{:post-footer :footer :gallery :interlude} key) [] {})]) contract/sections))}]
      (reset! rfdb/app-db {:content {}})
      (storage/write! :public-content bundle content/cache-options)
      (restore/begin! {:back? true})
      (set! content/request! (fn [_] (swap! *calls inc) (js/Promise.reject (js/Error. "Unexpected request"))))
      (-> (content/bootstrap!)
          (.then (fn [_] (is (= 0 @*calls)) (is (= (set contract/sections) (set (keys (:content @rfdb/app-db)))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! content/request! original) (reset! rfdb/app-db before)
                      (reset! restore/*context context) (reset! storage/*tracked tracked)
                      (storage/remove! :public-content content/cache-options) (done)))))))
