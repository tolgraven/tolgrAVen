(ns website.component-data-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [website.test-support :as support]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]



            [website.component :as component]
            [website.component.data :as data]
            [website.component.restore :as restore]
            [website.render-context :as context]
            [website.ssr.render :as ssr]
            [clojure.string :as string]
            [website.events]
            [website.subs]
            [website.component.sources]
            [website.content.client :as content]
            [website.modules.supabase.client :as supabase]
            [website.service-status :as status]
            [website.macros :refer-macros [defc]]))
(defonce *created (atom 0))
(defc <plain> [value] [:span value])
(defc <dependent>
  {:depends (fn [id] [{:source :fixture, :id id, :into [:fixture id]}
                      {:source :fixture, :id :parallel}])}
  [id]
  :let
  [_ (swap! *created inc)]
  [:p @(r/subscribe [:get :fixture id])])
(defn- flush! [] (support/settle!))
(defn- tick! [] (js/Promise. (fn [resolve _] (js/setTimeout resolve 20))))
(defn- clean!
  []
  (data/invalidate! (constantly true))
  (reset! data/*installed {})
  (swap! data/*sources dissoc :fixture))
(deftest preloading-is-parallel-shared-and-does-not-create-components
  (async
    done
    (->
      (go-promise
        (let [before @r/*app-db
              *calls (atom [])
              *resolve (atom {})
              container (.createElement js/document "div")
              root (await! (support/create-root! container))]
          (reset! *created 0)
          (data/register-source! :fixture
                                 {:load! (fn [{:keys [id]}]
                                           (swap! *calls conj id)
                                           (js/Promise. (fn [resolve _]
                                                          (swap! *resolve assoc id resolve))))})
          (let [first-load (component/preload! <dependent> :one)
                second-load (component/preload! <dependent> :one)]
            (is (true? (.-reagent-component <plain>)) "Reagent 2 function component, not a class")
            (is (empty? @*calls) "Adapters wait for the shared queue tick")
            (is (= 0 @*created) "Preloading does not construct the body")
            (await! (support/render! root [<dependent> :one]))
            (is (some? (.querySelector container
                                       "[aria-busy=true][aria-label=\"Loading content\"]")))
            (is (= [:one :parallel] @*calls) "Normal rendering allows the shared queue to run")
            (-> (tick!)
                (.then (fn [_]
                         (is (= [:one :parallel] @*calls) "Mount shares the preloaded requests")
                         ((get @*resolve :one) "First resource")
                         ((get @*resolve :parallel) "Second resource")
                         (js/Promise.all #js [first-load second-load])))
                (.then (fn [_] (tick!)))
                (.then (fn [_]
                         (go-promise (await! (flush!))
                                     (is (= "First resource" (.-textContent container)))
                                     (is (= 1 @*created))
                                     (is (= "First resource"
                                            (get-in @r/*app-db [:fixture :one]))))))
                (.catch #(is false (str %)))
                (.finally
                  (fn [] (support/unmount! root) (clean!) (reset! r/*app-db before) (done)))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest app-db-can-wait-before-mount-and-load-through-strapi
  (async done
         (-> (go-promise
               (let [before @r/*app-db
                     original content/ensure!
                     *calls (atom [])
                     resource {:source :app-db,
                               :path [:content :blog],
                               :load {:source :strapi, :keys [:blog]}}]
                 (reset! r/*app-db {})
                 (set! content/ensure!
                       (fn [keys]
                         (swap! *calls conj keys)
                         (swap! r/*app-db assoc-in [:content :blog] {:title "From CMS"})
                         (js/Promise.resolve nil)))
                 (-> (data/ensure! resource)
                     (.then (fn [value]
                              (is (= {:title "From CMS"} value))
                              (is (= [[:blog]] @*calls))
                              (let [pending (data/ensure! {:source :app-db, :path [:flag]})]
                                (swap! r/*app-db assoc :flag false)
                                pending)))
                     (.then (fn [value] (is (false? value) "False is loaded data, not missing")))
                     (.catch #(is false (str %)))
                     (.finally (fn []
                                 (set! content/ensure! original)
                                 (clean!)
                                 (reset! r/*app-db before)
                                 (done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest failures-are-visible-retryable-and-late-responses-cannot-install
  (async
    done
    (->
      (go-promise
        (let [before @r/*app-db
              *calls (atom 0)
              *late (atom nil)
              resource {:source :fixture, :id :failure, :into [:fixture :value], :timeout-ms 25}
              element (.createElement js/document "div")
              root (await! (support/create-root! element))]
          (data/register-source! :fixture
                                 {:load! (fn [_]
                                           (if (= 1 (swap! *calls inc))
                                             (js/Promise. (fn [resolve _] (reset! *late resolve)))
                                             (js/Promise.resolve "fresh")))})
          (-> (data/ensure! resource)
              (.then (fn [_] (is false "Should time out")))
              (.catch (fn [_]
                        (go-promise
                          (is (= :error (data/state [resource])))
                          (is (some #(and (vector? %) (= :component-data (first %)))
                                    (keys @status/*failures)))
                          (await! (support/render! root
                                                   [component/<data-body>
                                                    {:ns "fixture",
                                                     :name "dependent",
                                                     :options {:depends [resource]}} []
                                                    [:p "Recovered content"]]))
                          (is (some? (.querySelector element ".component-failed[role=alert]")))
                          (is (.includes (.-textContent element) "data could not be loaded"))
                          (.click (.querySelector element "button"))
                          (tick!))))
              (.then (fn [_] (@*late "stale") (tick!)))
              (.then (fn [_]
                       (go-promise (is (= "fresh" (get-in @r/*app-db [:fixture :value])))
                                   (is (= :ready (data/state [resource])))
                                   (is (= 2 @*calls))
                                   (await! (flush!))
                                   (is (nil? (.querySelector element ".component-failed")))
                                   (is (= "Recovered content" (.-textContent element))))))
              (.catch #(is false (str %)))
              (.finally
                (fn [] (support/unmount! root) (clean!) (reset! r/*app-db before) (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest supabase-adapter-does-not-install-old-account-data
  (async done
         (-> (go-promise
               (let [before @r/*app-db
                     original supabase/read-once!
                     original-client @supabase/*client
                     *resolve (atom nil)
                     resource {:source :supabase,
                               :query {:path-collection [:blog-posts]},
                               :into [:fixture :private]}]
                 (reset! supabase/*client #js {})
                 (set! supabase/read-once! (fn [_ success _] (reset! *resolve success)))
                 (let [pending (data/ensure! resource)
                       rejected (-> pending
                                    (.then (fn [_] (is false "Old client should be invalidated")))
                                    (.catch (fn [_] nil)))]
                   (-> (tick!)
                       (.then (fn [_]
                                (is (fn? @*resolve))
                                (reset! supabase/*client #js {})
                                (@*resolve {:secret "old account"})
                                rejected))
                       (.then (fn [_] (is (nil? (get-in @r/*app-db [:fixture :private])))))
                       (.catch #(is false (str %)))
                       (.finally (fn []
                                   (set! supabase/read-once! original)
                                   (reset! supabase/*client original-client)
                                   (clean!)
                                   (reset! r/*app-db before)
                                   (done)))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest newest-request-owns-a-shared-destination
  (async done
         (-> (go-promise
               (let [before @r/*app-db
                     *resolve (atom {})
                     old {:source :fixture, :id :old, :into [:fixture :shared]}
                     new (assoc old :id :new)]
                 (data/register-source!
                   :fixture
                   {:load! (fn [{:keys [id]}]
                             (js/Promise. (fn [resolve _] (swap! *resolve assoc id resolve))))})
                 (let [old-load (data/ensure! old)
                       new-load (data/ensure! new)]
                   (-> (tick!)
                       (.then (fn [_] ((get @*resolve :new) "new") new-load))
                       (.then (fn [_] ((get @*resolve :old) "old") old-load))
                       (.then (fn [_] (is (= "new" (get-in @r/*app-db [:fixture :shared])))))
                       (.catch #(is false (str %)))
                       (.finally (fn [] (clean!) (reset! r/*app-db before) (done)))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest url-data-is-installed-and-errors-render-a-retry
  (async
    done
    (->
      (go-promise
        (let [before @r/*app-db
              container (.createElement js/document "div")
              root (await! (support/create-root! container))
              *attempt (atom 0)
              view (component/create-component "fixture"
                                               "data"
                                               {:features [:data]}
                                               (fn [_] (fn [_] [:p "Ready after retry"])))
              resource {:source :fixture, :id :retry}]
          (data/register-source! :fixture
                                 {:load! (fn [_]
                                           (if (= 1 (swap! *attempt inc))
                                             (js/Promise.reject (js/Error. "fixture failure"))
                                             (js/Promise.resolve :ready)))})
          (await! (support/render! root [view {:depends [resource]}]))
          (-> (tick!)
              (.then (fn [_]
                       (go-promise (await! (flush!))
                                   (is (.includes (.-textContent container) "could not be loaded"))
                                   (let [button (.querySelector container "button")]
                                     (is (= "Attempt reload" (.-textContent button)))
                                     (.click button))
                                   (tick!))))
              (.then (fn [_]
                       (go-promise (await! (flush!))
                                   (is (= "Ready after retry" (.-textContent container)))
                                   (data/ensure! {:source :url,
                                                  :url "/fixtures/component-data.json",
                                                  :into [:fixture :url]}))))
              (.then (fn [value]
                       (is (= "Preloaded URL content" (:title value)))
                       (is (= value (get-in @r/*app-db [:fixture :url])))))
              (.catch #(is false (str %)))
              (.finally
                (fn [] (support/unmount! root) (clean!) (reset! r/*app-db before) (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))

(defn- tracking-resource [id]
  {:source :tracking-hydration, :id id, :into [:tracking-hydration id]})

(defc <tracking-dependent>
  {:depends (fn [id & _] [(tracking-resource id)])}
  [id gate *released? *attempts *commits]
  (swap! *attempts update id (fnil inc 0))
  (r/use-layout-effect (fn [] (swap! *commits conj id) nil) [])
  (when (and gate (not context/*server?*) (not @*released?)) (throw gate))
  [:p {:data-tracking-resource (name id)} @(r/subscribe [:get :tracking-hydration id])])

(r/defc <tracking-shell> [gate *released? *show-second? *attempts *commits *shell-committed?]
  (r/use-layout-effect (fn [] (reset! *shell-committed? true) nil) [])
  (let [pending-boundary (r/use-memo
                           #(r/as-element
                              [r/suspense {:fallback (r/as-element [:p {:data-tracking-fallback true} "Waiting"])}
                               [<tracking-dependent> :first gate *released? *attempts *commits]])
                           [gate *released? *attempts *commits])]
    [:div
     [:button {:on-click #(reset! *show-second? true)} "Acquire another ready resource"]
     pending-boundary
     (when @*show-second?
       [<tracking-dependent> :second nil *released? *attempts *commits])]))

(deftest restored-acquisition-does-not-track-an-uncommitted-hydration-owner
  (async done
    (let [restore-db! (r/make-restore-fn)
          before-context @restore/*context
          before-batching (r/batching-flush)
          before-source (get @data/*sources :tracking-hydration)
          before-error (.-error js/console)
          before-warn (.-warn js/console)
          element (.createElement js/document "div")
          *root (atom nil)
          *release (atom nil)
          gate (js/Promise. (fn [resolve _] (reset! *release resolve)))
          *released? (atom false)
          *show-second? (r/atom false)
          *attempts (atom {})
          *commits (atom [])
          *shell-committed? (atom false)
          *loads (atom [])
          *warnings (atom [])
          *recoveries (atom [])
          resources (mapv tracking-resource [:first :second])
          owned? #(= :tracking-hydration (:source %))
          form [<tracking-shell> gate *released? *show-second? *attempts *commits *shell-committed?]
          capture! (fn [original args]
                     (let [message (apply str (interpose " " args))]
                       (when (or (string/includes? message "hasn't mounted yet")
                                 (string/includes? message "hydrated")
                                 (string/includes? message "Hydration failed"))
                         (swap! *warnings conj message)))
                     (.apply original js/console (to-array args)))]
      (.appendChild (.-body js/document) element)
      (set! (.-error js/console) (fn [& args] (capture! before-error args)))
      (set! (.-warn js/console) (fn [& args] (capture! before-warn args)))
      (data/register-source! :tracking-hydration
        {:load! (fn [{:keys [id]}]
                  (swap! *loads conj id)
                  (js/Promise.resolve (name id)))})
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (await! (data/ensure-all! resources))
            ;; Real SSR has its own subscription cache. A new browser receives
            ;; installed values, not the worker's private resource entries.
            (let [db (await! (support/state-at! []))]
              (set! (.-innerHTML element) (ssr/html! db form {:modules {}})))
            (data/invalidate! owned?)
            (doseq [resource resources]
              (r/dispatch-sync [:component-data/install (:into resource) (name (:id resource))]))
            (reset! *attempts {})
            (reset! *commits [])
            (restore/begin! {:hydrate? true})
            (let [server-node (.querySelector element "[data-tracking-resource='first']")]
              (is (= "first" (.-textContent server-node)))
              (reset! *root (r/hydrate-root element (restore/document-form form)
                             {:on-recoverable-error #(swap! *recoveries conj (str %))}))
              (await! (support/wait-for! #(and @*shell-committed? (pos? (:first @*attempts 0)))
                                        "The actual first resource owner did not begin pending hydration"))
              (is (not (some #{:first} @*commits)) "The first owner has rendered but has not committed")
              (is (identical? server-node (.querySelector element "[data-tracking-resource='first']")))
              (.click (.querySelector element "button"))
              (await! (support/wait-for! #(some #{:second} @*commits)
                                        "The second already-installed resource did not commit"))
              ;; Let real Reagent animation frames flush any incidental watch
              ;; notification queued by the second synchronous restoration.
              (await! (support/settle!))
              (await! (support/settle!))
              (is (empty? @*warnings) (str "Unexpected precommit/hydration warnings: " (pr-str @*warnings)))
              (is (empty? @*recoveries))
              (is (= :ready (data/state resources)))
              (is (= [:first :second] @*loads) "Hydration reuses supplied values without new source acquisition")
              (is (identical? server-node (.querySelector element "[data-tracking-resource='first']")))
              (is (nil? (.querySelector element "[data-tracking-fallback]"))
                  "An unrelated ready source must not replace retained SSR with loading UI")
              (reset! *released? true)
              (@*release nil)
              (await! (support/wait-for! #(some #{:first} @*commits) "The original pending owner did not finish"))
              (is (identical? server-node (.querySelector element "[data-tracking-resource='first']")))
              (is (= "second" (.-textContent (.querySelector element "[data-tracking-resource='second']"))))
              (is (empty? @*warnings))
              (is (empty? @*recoveries))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (r/unmount @*root))
                      (reset! *released? true)
                      (@*release nil)
                      (data/invalidate! owned?)
                      (if before-source (data/register-source! :tracking-hydration before-source)
                        (swap! data/*sources dissoc :tracking-hydration))
                      (reset! restore/*context before-context)
                      (r/set-batching-flush! before-batching)
                      (set! (.-error js/console) before-error)
                      (set! (.-warn js/console) before-warn)
                      (.remove element)
                      (restore-db!)
                      (done)))))))
