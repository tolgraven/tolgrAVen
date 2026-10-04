(ns tolgraven.component-data-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [react-dom :as react-dom]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [re-frame.db :as rfdb]
            [tolgraven.component :as component]
            [tolgraven.component.data :as data]
            [tolgraven.component.sources]
            [tolgraven.content.client :as content]
            [tolgraven.supabase.client :as supabase]
            [tolgraven.service-status :as status]
            [tolgraven.macros :refer-macros [defc]]))

(defonce *created (atom 0))
(defc <plain> [value] [:span value])
(defc <dependent>
  {:depends (fn [id] [{:source :fixture :id id :into [:fixture id]}
                      {:source :fixture :id :parallel}])}
  [id]
  :let [_ (swap! *created inc)]
  [:p (get-in @rfdb/app-db [:fixture id])])

(defn- flush! [] (react-dom/flushSync #(r/flush)))
(defn- tick! [] (js/Promise. (fn [resolve _] (js/setTimeout resolve 20))))
(defn- clean! []
  (data/invalidate! (constantly true))
  (reset! data/*installed {})
  (swap! data/*sources dissoc :fixture))

(deftest preloading-is-parallel-shared-and-does-not-create-components
  (async done
    (let [before @rfdb/app-db *calls (atom []) *resolve (atom {})
          container (.createElement js/document "div") root (dom/create-root container)]
      (reset! *created 0)
      (data/register-source! :fixture
                             {:load! (fn [{:keys [id]}]
                                       (swap! *calls conj id)
                                       (js/Promise. (fn [resolve _] (swap! *resolve assoc id resolve))))})
      (let [first-load (component/preload! <dependent> :one)
            second-load (component/preload! <dependent> :one)]
        (is (true? (.-reagent-component <plain>)) "Reagent 2 function component, not a class")
        (is (empty? @*calls) "Adapters wait for the shared queue tick")
        (is (= 0 @*created) "Preloading does not construct the body")
        (react-dom/flushSync #(dom/render root [<dependent> :one]))
        (is (.includes (.-textContent container) "Loading content"))
        (is (empty? @*calls))
        (-> (tick!)
            (.then (fn [_]
                     (is (= [:one :parallel] @*calls) "Mount shares the preloaded requests")
                     ((get @*resolve :one) "First resource")
                     ((get @*resolve :parallel) "Second resource")
                     (js/Promise.all #js [first-load second-load])))
            (.then (fn [_] (tick!)))
            (.then (fn [_]
                     (flush!)
                     (is (= "First resource" (.-textContent container)))
                     (is (= 1 @*created))
                     (is (= "First resource" (get-in @rfdb/app-db [:fixture :one])))))
            (.catch #(is false (str %)))
            (.finally (fn [] (react-dom/flushSync #(dom/unmount root))
                        (clean!) (reset! rfdb/app-db before) (done))))))))

(deftest app-db-can-wait-before-mount-and-load-through-strapi
  (async done
    (let [before @rfdb/app-db original content/ensure! *calls (atom [])
          resource {:source :app-db :path [:content :blog]
                    :load {:source :strapi :keys [:blog]}}]
      (reset! rfdb/app-db {})
      (set! content/ensure! (fn [keys]
                             (swap! *calls conj keys)
                             (swap! rfdb/app-db assoc-in [:content :blog] {:title "From CMS"})
                             (js/Promise.resolve nil)))
      (-> (data/ensure! resource)
          (.then (fn [value]
                   (is (= {:title "From CMS"} value))
                   (is (= [[:blog]] @*calls))
                   (let [pending (data/ensure! {:source :app-db :path [:flag]})]
                     (swap! rfdb/app-db assoc :flag false)
                     pending)))
          (.then (fn [value] (is (false? value) "False is loaded data, not missing")))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! content/ensure! original)
                      (clean!) (reset! rfdb/app-db before) (done)))))))

(deftest failures-are-visible-retryable-and-late-responses-cannot-install
  (async done
    (let [before @rfdb/app-db *calls (atom 0) *late (atom nil)
          resource {:source :fixture :id :failure :into [:fixture :value] :timeout-ms 25}]
      (data/register-source! :fixture
                            {:load! (fn [_]
                                      (if (= 1 (swap! *calls inc))
                                        (js/Promise. (fn [resolve _] (reset! *late resolve)))
                                        (js/Promise.resolve "fresh")))})
      (-> (data/ensure! resource)
          (.then (fn [_] (is false "Should time out")))
          (.catch (fn [_]
                    (is (= :error (data/state [resource])))
                    (is (some #(= :component-data (first %)) (keys @status/*failures)))
                    (data/retry! [resource])))
          (.then (fn [_]
                   (@*late "stale")
                   (tick!)))
          (.then (fn [_]
                   (is (= "fresh" (get-in @rfdb/app-db [:fixture :value])))
                   (is (= :ready (data/state [resource])))
                   (is (= 2 @*calls))))
          (.catch #(is false (str %)))
          (.finally (fn [] (clean!) (reset! rfdb/app-db before) (done)))))))

(deftest supabase-adapter-does-not-install-old-account-data
  (async done
    (let [before @rfdb/app-db original supabase/read-once! original-client @supabase/*client
          *resolve (atom nil) resource {:source :supabase :query {:path-collection [:blog-posts]}
                                        :into [:fixture :private]}]
      (reset! supabase/*client #js {})
      (set! supabase/read-once! (fn [_ success _] (reset! *resolve success)))
      (let [pending (data/ensure! resource)
            rejected (-> pending (.then (fn [_] (is false "Old client should be invalidated")))
                         (.catch (fn [_] nil)))]
        (-> (tick!)
            (.then (fn [_]
                     (is (fn? @*resolve))
                     (reset! supabase/*client #js {})
                     (@*resolve {:secret "old account"})
                     rejected))
            (.then (fn [_]
                     (is (nil? (get-in @rfdb/app-db [:fixture :private])))))
            (.catch #(is false (str %)))
            (.finally (fn [] (set! supabase/read-once! original)
                        (reset! supabase/*client original-client)
                        (clean!) (reset! rfdb/app-db before) (done))))))))

(deftest newest-request-owns-a-shared-destination
  (async done
    (let [before @rfdb/app-db *resolve (atom {})
          old {:source :fixture :id :old :into [:fixture :shared]}
          new (assoc old :id :new)]
      (data/register-source! :fixture
                            {:load! (fn [{:keys [id]}]
                                      (js/Promise. (fn [resolve _] (swap! *resolve assoc id resolve))))})
      (let [old-load (data/ensure! old) new-load (data/ensure! new)]
        (-> (tick!)
            (.then (fn [_] ((get @*resolve :new) "new") new-load))
            (.then (fn [_] ((get @*resolve :old) "old") old-load))
            (.then (fn [_] (is (= "new" (get-in @rfdb/app-db [:fixture :shared])))))
            (.catch #(is false (str %)))
            (.finally (fn [] (clean!) (reset! rfdb/app-db before) (done))))))))

(deftest url-data-is-installed-and-errors-render-a-retry
  (async done
    (let [before @rfdb/app-db container (.createElement js/document "div")
          root (dom/create-root container) *attempt (atom 0)
          view (component/create-component "fixture" "data" {:features [:data]}
                                           (fn [_] (fn [_] [:p "Ready after retry"])))
          resource {:source :fixture :id :retry}]
      (data/register-source! :fixture
                            {:load! (fn [_] (if (= 1 (swap! *attempt inc))
                                             (js/Promise.reject (js/Error. "fixture failure"))
                                             (js/Promise.resolve :ready)))})
      (react-dom/flushSync #(dom/render root [view {:depends [resource]}]))
      (-> (tick!)
          (.then (fn [_]
                   (flush!)
                   (is (.includes (.-textContent container) "could not be loaded"))
                   (let [button (.querySelector container "button")]
                     (is (= "Retry data" (.-textContent button)))
                     (.click button))
                   (tick!)))
          (.then (fn [_]
                   (flush!)
                   (is (= "Ready after retry" (.-textContent container)))
                   (data/ensure! {:source :url :url "/fixtures/component-data.json"
                                  :into [:fixture :url]})))
          (.then (fn [value]
                   (is (= "Preloaded URL content" (:title value)))
                   (is (= value (get-in @rfdb/app-db [:fixture :url])))))
          (.catch #(is false (str %)))
          (.finally (fn [] (react-dom/flushSync #(dom/unmount root))
                      (clean!) (reset! rfdb/app-db before) (done)))))))
