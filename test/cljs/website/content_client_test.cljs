(ns website.content-client-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [website.test-support :as support]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]

            [website.test-support :refer [subscription-value!]]
            [ajax.core :as ajax]
            [website.service-status :as status]
            [website.content.client :as content]
            [website.events]
            [website.subs]
            [website.loader :as loader]
            [website.component.data :as data]
            [website.component.sources]
            [website.content.contract :as contract]
            [website.component.storage :as storage]
            [website.component.restore :as restore]
            [shadow.lazy :as lazy]))
(deftest concurrent-module-content-requests-share-a-fetch
  (async
    done
    (-> (go-promise
          (let [before @r/*app-db
                calls (atom [])
                original content/request!]
            (reset! content/*pending {})
            (reset! r/*app-db {:content {:document {:title "Loaded"}}})
            (set! content/request! (fn [ks] (swap! calls conj ks) (js/Promise.resolve nil)))
            (let [a (content/ensure! [:document :cv])
                  b (content/ensure! [:cv :blog])]
              (is (empty? @calls) "No work until the next tick")
              (-> (js/Promise.all #js [a b])
                  (.then (fn [_]
                           (is (= [[:blog :cv]] @calls)
                               "Disjoint sections batch; duplicates disappear")
                           (is (empty? @content/*pending))))
                  (.catch #(is false (str %)))
                  (.finally
                    (fn [] (set! content/request! original) (reset! r/*app-db before) (done)))))))
        (.catch (fn [error] (is false (str error)) (done))))))
(deftest module-init-waits-for-required-content
  (async done
    (-> (go-promise
          (let [id (keyword (str (random-uuid)))
                restore! (r/make-restore-fn)
                *initialized? (atom false) *started? (atom false) *resolve (atom nil)
                pending (js/Promise. (fn [resolve _] (reset! *resolve resolve)))
                resource {:source :strapi :keys [:cv] :key id}
                spec {:id id :depends [resource] :init #(reset! *initialized? true)}
                loadable (reify lazy/ILoadable (ready? [_] true) IDeref (-deref [_] spec))
                original content/ensure!]
            (try
              (r/dispatch-sync [:init/app-db])
              (set! content/ensure! (fn [ks] (is (= [:cv] ks)) (reset! *started? true) pending))
              (let [loaded (r/with-framework-bindings [loader/modules {id loadable}] (loader/load! {:module id}))]
                (await! (support/settle!))
                (is @*started? "The declared managed source was actually acquired")
                (is (false? @*initialized?) "Init waits while that source is pending")
                (@*resolve nil)
                (await! loaded)
                (is @*initialized?))
              (finally
                (set! content/ensure! original)
                (swap! loader/*loads dissoc id)
                (data/invalidate! #{resource})
                (restore!)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(deftest bootstrap-rejects-content-outside-the-public-contract
  (is (false? (content/valid-bundle? {:version 1, :content {:secrets {}}} [])))
  (is (false? (content/valid-bundle? {:version 1, :content {:story nil}} [:story])))
  (is (false? (content/valid-bundle? {:version 1, :content {:cv {:cv {:timeline 123}}}} [:cv]))))
(deftest failed-content-is-visible-deduplicated-and-retryable
  (async
    done
    (->
      (go-promise
        (let [before @r/*app-db
              failures @status/*failures
              original-get ajax/GET
              original-dispatch (r/dispatch-function)
              events (atom [])
              succeed? (atom false)
              rejected! (fn [promise]
                          (-> promise
                              (.then (fn [_] (is false "Request should fail")))
                              (.catch (fn [_] nil))))]
          (reset! status/*failures {})
          (reset! content/*pending {})
          (reset! r/*app-db {:content {:cv {:title "Keep me"}}})
          (r/set-dispatch-function! #(swap! events conj %))
          (set! ajax/GET
                (fn [_ & [options]]
                  (if (= true @succeed?)
                    ((:handler options) {:version 1, :content {:story {:title "Recovered"}}})
                    (if (= :invalid @succeed?)
                      ((:handler options) {:version 999, :content {}})
                      ((:error-handler options) {:status 503})))))
          (-> (rejected! (content/ensure! [:story]))
              (.then (fn []
                       (is (empty? @content/*pending))
                       (is (= "Keep me" (get-in @r/*app-db [:content :cv :title])))
                       (is (contains? @status/*failures [:strapi [:story]]))
                       (reset! succeed? :invalid)
                       (rejected! (content/ensure! [:story]))))
              (.then (fn []
                       (is (= 1 (count (filter #(= :diag/new (first %)) @events))))
                       (reset! succeed? true)
                       (content/ensure! [:story])))
              (.then (fn []
                       (is (empty? @status/*failures))
                       (is (= "Recovered" (get-in @r/*app-db [:content :story :title])))))
              (.catch #(is false (str %)))
              (.finally (fn []
                          (set! ajax/GET original-get)
                          (r/set-dispatch-function! original-dispatch)
                          (reset! status/*failures failures)
                          (reset! r/*app-db before)
                          (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest module-data-starts-with-code-and-can-retry
  (async
    done
    (->
      (go-promise
        (let [id (keyword (str (random-uuid)))
              *events (atom [])
              *code (atom nil)
              *attempt (atom 0)
              resource {:source :module-test}
              original-modules loader/modules
              original-manifest contract/module-dependencies
              original-load lazy/load
              spec {:id id :init #(swap! *events conj :init)}
              loadable (reify
                         lazy/ILoadable
                           (ready? [_] false)
                         IDeref
                           (-deref [_] spec))]
          (data/register-source! :module-test
                                 {:load! (fn [_]
                                           (swap! *events conj :data)
                                           (if (= 1 (swap! *attempt inc))
                                             (js/Promise.reject (js/Error. "First load fails"))
                                             (js/Promise.resolve :loaded)))})
          (set! loader/modules {id loadable})
          (set! contract/module-dependencies {id [resource]})
          (set!
            lazy/load
            (fn
              ([_] (swap! *events conj :code) (js/Promise. (fn [resolve _] (reset! *code resolve))))
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
                (.then (fn [_] (is (= 2 @*attempt)) (is (= :init (last @*events)))))
                (.catch #(is false (str %)))
                (.finally (fn []
                            (set! loader/modules original-modules)
                            (set! contract/module-dependencies original-manifest)
                            (set! lazy/load original-load)
                            (swap! loader/*loads dissoc id)
                            (data/invalidate! #{resource})
                            (swap! data/*sources dissoc :module-test)
                            (done)))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest external-back-restores-public-content-before-network-loads
  (async
    done
    (->
      (go-promise
        (let [before @r/*app-db
              context @restore/*context
              tracked @storage/*tracked
              original content/request!
              *calls (atom 0)
              bundle {:version contract/version,
                      :content
                        (into
                          {}
                          (map (fn [key]
                                 [key (case key
                                        :github-settings {:user "example", :repo "website"}
                                        (if (#{:post-footer :footer :gallery :interlude} key) [] {}))])
                            contract/sections))}]
          (reset! r/*app-db {:content {}})
          (storage/write! :public-content bundle content/cache-options)
          (restore/begin! {:back? true})
          (set! content/request!
                (fn [_] (swap! *calls inc) (js/Promise.reject (js/Error. "Unexpected request"))))
          (-> (content/bootstrap!)
              (.then (fn [_]
                       (is (= 0 @*calls))
                       (is (= (set contract/sections) (set (keys (:content @r/*app-db)))))))
              (.catch #(is false (str %)))
              (.finally (fn []
                          (set! content/request! original)
                          (reset! r/*app-db before)
                          (reset! restore/*context context)
                          (reset! storage/*tracked tracked)
                          (storage/remove! :public-content content/cache-options)
                          (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest content-subscription-only-prefetches-declared-cms-sections
  (async done
         (-> (go-promise
               (let [before @r/*app-db
                     *requests (atom [])]
                 (r/clear-subscription-cache!)
                 (reset! r/*app-db {:content {:github {:repo []}}})
                 (try (r/with-framework-bindings [r/dispatch #(swap! *requests conj %)]
                        (is (= {:repo []} (await! (subscription-value! [:content [:github]]))))
                        (is (nil? (await! (subscription-value! [:content [:instagram]]))))
                        (is (nil? (await! (subscription-value! [:content [:github-settings]]))))
                        (is (nil? (await! (subscription-value! [:content [:codex-profile]]))))
                        (is (nil? (await! (subscription-value! [:content [:story]]))))
                        (is (= [[:content/load [:story]]] @*requests)))
                      (finally (r/clear-subscription-cache!) (reset! r/*app-db before)))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))

(deftest module-installation-is-shared-and-finishes-before-code-is-renderable
  (async done
    (let [id (keyword (str (random-uuid)))
          original loader/modules
          *finish (atom nil)
          *installs (atom 0)
          *started (atom nil)
          started (js/Promise. (fn [resolve _] (reset! *started resolve)))
          spec {:install #(do (swap! *installs inc)
                              (js/Promise. (fn [resolve _]
                                             (reset! *finish resolve)
                                             (@*started nil))))}
          loadable (reify lazy/ILoadable (ready? [_] true)
                         IDeref (-deref [_] spec))]
      (set! loader/modules {id loadable})
      (let [first-load (loader/acquire-code! id)
            second-load (loader/acquire-code! id)]
        (is (identical? first-load second-load))
        (-> started
            (.then (fn [_]
                     (is (= 1 @*installs))
                     (is (not (loader/ready? id)))
                     (is (nil? (loader/code-spec id)))
                     (@*finish nil)
                     first-load))
            (.then (fn [loaded]
                     (is (= (assoc spec :id id) loaded))
                     (is (true? (loader/ready? id)))
                     (is (= (assoc spec :id id) (loader/code-spec id)))))
            (.catch (fn [error] (is false (str error))))
            (.finally (fn []
                        (set! loader/modules original)
                        (swap! loader/*code-loads dissoc id)
                        (swap! loader/*installed disj id)
                        (done))))))))

(deftest network-ssr-installs-content-without-waiting-for-disk-or-transport
  (async done
    (-> (go-promise
          (let [restore! (r/make-restore-fn)
                *tracked (atom [])
                snapshot {:content {:document {:title "Server title"}
                                    :blog {}}}]
            (try
              (r/dispatch-sync [:init/app-db])
              (r/with-framework-bindings [storage/ready! #(throw (js/Error. "SSR must not await disk"))
                            storage/track! (fn [id _ _] (swap! *tracked conj id))
                            content/request! (fn [_] (throw (js/Error. "SSR must not refetch its pair")))]
                (let [pending (content/bootstrap-server! snapshot)]
                  (is (= (contract/normalize-content (:content snapshot)) (:content @r/*app-db)))
                  (is (= :ready (get-in @r/*app-db [:state :content :status])))
                  (await! pending)
                  (is (= [:public-content] @*tracked))))
              (finally (restore!)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest failed-batched-content-does-not-reject-unrelated-consumers
  (async done
    (-> (go-promise
          (let [before @r/*app-db
                failures @status/*failures
                original content/request!
                *calls (atom [])]
            (reset! content/*pending {})
            (reset! status/*failures {})
            (reset! r/*app-db {:content {:cv {:title "Retained"}}})
            (set! content/request!
                  (fn [ks]
                    (swap! *calls conj ks)
                    (if (= ks [:story])
                      (do (r/dispatch-sync [:content/install
                                            {:version contract/version
                                             :content {:story {:title "Available"}}}])
                          (js/Promise.resolve nil))
                      (js/Promise.reject (js/Error. "Missing module config")))))
            (let [healthy (content/ensure! [:story])
                  unavailable (-> (content/ensure! [:docs])
                                  (.then (fn [_] (is false "Missing content must reject its owner")))
                                  (.catch (fn [_] nil)))]
              (-> (js/Promise.all #js [healthy unavailable])
                  (.then (fn [_]
                           (is (= [[:docs :story] [:docs] [:story]] @*calls))
                           (is (= "Available" (get-in @r/*app-db [:content :story :title])))
                           (is (= "Retained" (get-in @r/*app-db [:content :cv :title])))
                           (is (= #{[:strapi [:docs]]} (set (keys @status/*failures))))
                           (is (empty? @content/*pending))))
                  (.finally (fn []
                              (set! content/request! original)
                              (reset! r/*app-db before)
                              (reset! status/*failures failures)))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest startup-content-does-not-gate-the-shell-on-route-modules
  (is (= contract/shell-content (content/bootstrap-keys nil "/test/liquid-glass")))
  (is (= contract/shell-content (content/bootstrap-keys nil "/cv")))
  (is (= contract/shell-content (content/bootstrap-keys nil "/about")))
  (is (not (some #{:intro :story :strava :cv :docs :github-settings :codex-profile}
                 (content/bootstrap-keys nil "/"))))
  (is (= [:document] (content/bootstrap-keys {:deferred? true, :content {:document {}}} "/cv"))))

(deftest managed-content-waits-for-delayed-section-recovery
  (async done
    (let [before @r/*app-db
          failures @status/*failures
          original-request content/request!
          original-timeout data/default-timeout-ms
          original-set-timeout js/setTimeout
          original-clear-timeout js/clearTimeout
          adapter (get @data/*sources :strapi)
          resource {:source :strapi, :keys [:story]}
          *now (atom 0)
          *timers (atom {})
          *timer-ids (atom #{})
          *deadline? (atom false)
          *requests (atom [])
          *rejects (atom [])
          *closed? (atom false)
          *consumers (atom [])
          settled! (fn [promise] (.catch (js/Promise.resolve promise) (fn [_] nil)))
          schedule! (fn [callback milliseconds]
                      (let [id (js-obj)]
                        (swap! *timer-ids conj id)
                        (swap! *timers assoc id {:at (+ @*now milliseconds), :callback callback})
                        id))
          advance! (fn [time]
                     (loop []
                       (when-let [[id {:keys [at callback]}]
                                  (first (sort-by (comp :at val)
                                           (filter #(<= (:at (val %)) time) @*timers)))]
                         (reset! *now at)
                         (swap! *timers dissoc id)
                         (callback)
                         (recur)))
                     (reset! *now time))]
      ;; Capture only this acquisition's deadline. Transport gates share its
      ;; clock; unrelated timers and native queue ticks keep their own scheduler.
      (set! js/setTimeout
        (fn [callback milliseconds & args]
          (if (and @*deadline? (#{50 150} milliseconds))
            (schedule! callback milliseconds)
            (.apply original-set-timeout js/window (to-array (into [callback milliseconds] args))))))
      (set! js/clearTimeout
        (fn [id]
          (if (contains? @*timer-ids id)
            (swap! *timers dissoc id)
            (original-clear-timeout id))))
      (reset! r/*app-db {:content {}})
      (reset! content/*pending {})
      (reset! status/*failures {})
      (set! data/default-timeout-ms 50)
      (data/register-source! :strapi (assoc adapter :timeout-ms 150))
      (set! content/request!
        (fn [ks]
          (swap! *requests conj ks)
          (if @*closed?
            (js/Promise.reject (js/Error. "Fixture closed"))
            (js/Promise.
              (fn [resolve reject]
                (swap! *rejects conj reject)
                (schedule!
                  (fn []
                    (if (= ks [:story])
                      (do (r/dispatch-sync [:content/install
                                            {:version contract/version
                                             :content {:story {:title "Recovered"}}}])
                          (resolve nil))
                      (reject (js/Error. "Unavailable module config"))))
                  35))))))
      (-> (go-promise
            (let [healthy (try
                            (reset! *deadline? true)
                            (data/ensure! resource)
                            (finally (reset! *deadline? false)))
                  unavailable (settled! (content/ensure! [:docs]))]
              ;; Attach rejection handlers before advancing the deadline clock.
              (reset! *consumers [(settled! healthy) unavailable])
              (await! (support/wait-for! #(= [[:docs :story]] @*requests)))
              (advance! 35)
              (await! (support/wait-for! #(= [[:docs :story] [:docs] [:story]] @*requests)))
              (advance! 50)
              (await! (js/Promise.resolve nil))
              (is (= :loading (:status (data/snapshot resource)))
                  "Recovery remains pending beyond the ordinary 50ms budget")
              (is (not (contains? (:content @r/*app-db) :story)))
              (advance! 70)
              (let [values (await! (js/Promise.all #js [healthy unavailable]))]
                (is (= "Recovered" (get-in (aget values 0) [:story :title])))
                (is (= :ready (:status (data/snapshot resource))))
                (is (= #{[:strapi [:docs]]} (set (keys @status/*failures)))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              ;; A failed assertion/wait must not leave a transport callback that
              ;; installs content after this fixture restores the application's db.
              (reset! *closed? true)
              (let [pending (concat @*consumers (vals @content/*pending))]
                (data/invalidate! #{resource})
                (doseq [reject @*rejects] (reject (js/Error. "Fixture closed")))
                (reset! *timers {})
                (js/Promise.all (into-array (map settled! pending))))))
          (.finally
            (fn []
              (set! js/setTimeout original-set-timeout)
              (set! js/clearTimeout original-clear-timeout)
              (data/register-source! :strapi adapter)
              (set! data/default-timeout-ms original-timeout)
              (set! content/request! original-request)
              (reset! r/*app-db before)
              (reset! status/*failures failures)
              (done)))))))

(deftest cms-managed-deadline-includes-both-transport-attempts
  (is (> (:timeout-ms (get @data/*sources :strapi)) (* 2 content/request-timeout-ms))))

(deftest missing-route-content-does-not-reject-client-bootstrap
  (async done
    (-> (go-promise
          (let [before @r/*app-db
                failures @status/*failures
                context @restore/*context
                tracked @storage/*tracked
                original content/request!
                *calls (atom [])]
            (reset! content/*pending {})
            (reset! status/*failures {})
            (reset! r/*app-db {:content {}})
            (restore/begin! {:back? false})
            (set! content/request!
                  (fn [ks]
                    (swap! *calls conj ks)
                    (if (some #{:story} ks)
                      (js/Promise.reject (js/Error. "Missing route content"))
                      (let [bundle {:version contract/version
                                    :content (into {} (map (fn [k] [k (if (#{:footer :post-footer} k) [] {})])) ks)}]
                        (r/dispatch-sync [:content/install bundle])
                        (js/Promise.resolve bundle)))))
            (-> (content/bootstrap!)
                (.then (fn [_]
                         (is (= [(vec (sort contract/shell-content))] @*calls))
                         (is (= :ready (get-in @r/*app-db [:state :content :status])))
                         (-> (content/ensure! [:story])
                             (.then (fn [_] (is false "The unavailable route dependency must fail")))
                             (.catch (fn [_] nil)))))
                (.then (fn [_]
                         (is (every? #(contains? (:content @r/*app-db) %) contract/shell-content))
                         (is (= :ready (get-in @r/*app-db [:state :content :status])))
                         (is (= #{[:strapi [:story]]} (set (keys @status/*failures))))))
                (.finally (fn []
                            (set! content/request! original)
                            (reset! r/*app-db before)
                            (reset! status/*failures failures)
                            (reset! restore/*context context)
                            (reset! storage/*tracked tracked)
                            (storage/remove! :public-content content/cache-options))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
