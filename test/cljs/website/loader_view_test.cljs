(ns website.loader-view-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [shadow.lazy :as lazy]
            [clojure.string :as string]
            [website.react :as r]
            [website.loader :as loader]
            [website.loader.code :as code]
            [website.loader.view :as view]
            [website.loader.styles :as styles]
            [website.module-transport :as transport]
            [website.service-status :as status]
            [website.component.restore :as restore]
            [website.validation.runtime :as validation]
            [website.render-context :as context]
            [website.test-support :as support]
            [website.events]
            [website.subs]))

(r/defc <loaded> [] [:p {:data-owned-view true} "Ready"])

(r/defc <fixture> [id export]
  (view/form id export [] [:p {:data-pending-view true} "Pending"]))

(deftest fetched-module-waits-for-its-css-and-install-hook
  (async done
    (let [id (keyword (str "lightweight-" (random-uuid)))
          *css-release (atom nil)
          *install-release (atom nil)
          *css-ready? (atom false)
          *install-started? (atom false)
          css (js/Promise. (fn [resolve _] (reset! *css-release resolve)))
          installed (js/Promise. (fn [resolve _] (reset! *install-release resolve)))
          spec {:id id
                :view {:view <loaded>}
                :install (fn [] (reset! *install-started? true) installed)}
          loadable (reify lazy/ILoadable
                     (ready? [_] true)
                     IDeref (-deref [_] spec))
          before {:modules loader/modules
                  :code-modules code/modules
                  :css styles/acquire!
                  :ready? styles/ready?
                  :db @r/*app-db
                  :validation @validation/*enabled?
                  :loads @loader/*loads
                  :code-loads @loader/*code-loads
                  :installed @loader/*installed}
          element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (reset! validation/*enabled? false)
      (set! loader/modules (assoc loader/modules id loadable))
      (set! code/modules (assoc code/modules id loadable))
      (set! styles/acquire! (fn [module] (if (= id module) css ((:css before) module))))
      (set! styles/ready? (fn [module] (if (= id module) @*css-ready? ((:ready? before) module))))
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [<fixture> id :view]))
              (await! (support/wait-for! #(contains? @loader/*code-loads id)))
              (is (lazy/ready? loadable) "JavaScript is already fetched")
              (is (some? (.querySelector element "[data-pending-view]")) "CSS pending keeps the fallback")
              (is (nil? (.querySelector element "[data-owned-view]")))
              (reset! *css-ready? true)
              (@*css-release nil)
              (await! (support/wait-for! #(deref *install-started?)))
              (await! (support/settle!))
              (is (some? (.querySelector element "[data-pending-view]")) "Completed CSS still waits for installation")
              (is (nil? (.querySelector element "[data-owned-view]")))
              (@*install-release nil)
              (await! (support/wait-for! #(.querySelector element "[data-owned-view]")))
              (is (contains? @loader/*installed id))
              (is (nil? (.querySelector element "[data-pending-view]")))))
          (.catch #(is false (.-message %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (set! loader/modules (:modules before))
                      (set! code/modules (:code-modules before))
                      (set! styles/acquire! (:css before))
                      (set! styles/ready? (:ready? before))
                      (reset! loader/*loads (:loads before))
                      (reset! loader/*code-loads (:code-loads before))
                      (reset! loader/*installed (:installed before))
                      (r/clear-subscription-cache!)
                      (reset! r/*app-db (:db before))
                      (reset! validation/*enabled? (:validation before))
                      (done)))))))

(deftest ssr-records-only-resolved-visible-exports
  (let [*exports (atom {})
        modules {:home {:id :home, :view {:float-image <loaded>}}}
        before @loader/*code-loads
        before-code code/modules
        unselected (reify lazy/ILoadable
                     (ready? [_] true)
                     IDeref (-deref [_] {:id :unselected, :view {:view <loaded>}}))]
    (set! code/modules (assoc code/modules :unselected unselected))
    (try
      (binding [context/*server?* true
                context/*modules* modules
                context/*rendered-views* *exports]
        (let [html (r/render-to-string [<fixture> :home :float-image])]
          (is (.includes html "Ready"))
          (is (= {:home #{:float-image}} @*exports)))
        (r/render-to-string [<fixture> :home :missing])
        (r/render-to-string [<fixture> :unknown :view])
        (let [html (r/render-to-string [<fixture> :unselected :view])]
          (is (.includes html "Pending") "Fetched browser code cannot escape the server's selected inventory")
          (is (not (.includes html "Ready"))))
        (is (= {:home #{:float-image}} @*exports) "Fallbacks do not invent visible owners")
        (is (= before @loader/*code-loads) "Server rendering starts no browser acquisition"))
      (finally (set! code/modules before-code)))))


(r/defc <precommit-target> [*committed?]
  (let [[clicked? set-clicked!] (r/use-state false)]
    (r/use-layout-effect (fn [] (reset! *committed? true) nil) [])
    [:button {:data-loader-retained-target true
              :on-click #(set-clicked! true)}
     (if clicked? "Clicked" "Server target")]))

(r/defc <held-loader-descendant> [gate *released? *pending?]
  (when (and (not context/*server?*) (not @*released?))
    (reset! *pending? true)
    (throw gate))
  [:span {:data-loader-ancestor-ready true} "Ancestor ready"])

(r/defc <held-loader-scene> [module gate *released? *pending? *committed? *shell? *input]
  (r/use-layout-effect (fn [] (reset! *shell? true) nil) [])
  (let [pending (r/use-memo
                  #(r/as-element
                     [r/suspense {:fallback (r/as-element [:p {:data-loader-ancestor-fallback true} "Waiting"])}
                      [:article
                       (loader/component-vector {:module module :load-on :event} [*committed?])
                       [<held-loader-descendant> gate *released? *pending?]]])
                  [module gate *released? *pending? *committed?])]
    [:div
     [:input {:data-loader-urgent-input true
              :value @*input
              :on-change #(reset! *input (.. % -target -value))}]
     [:output {:data-loader-urgent-value true} @*input]
     [:button {:data-acquire-held-loader true
               :on-click #(r/dispatch [:loader/acquire module])} "Acquire section"]
     pending]))

(defn- check-loader-retention! [{:keys [element server-node *removed observer]}]
  (doseq [record (array-seq (.takeRecords observer))
          removed (array-seq (.-removedNodes record))]
    (when (or (identical? removed server-node) (.contains removed server-node))
      (reset! *removed true)))
  (is (identical? server-node (.querySelector element "[data-loader-retained-target]"))))

(defn- prepare-held-loader! [{:keys [element form *root *pending? *shell? *committed? *warnings *recoveries module *reader]
                            :as fixture}]
  (go-promise
    (r/dispatch-sync [:init/app-db])
    ;; Cached Home already records activation before the code-ready update.
    (r/dispatch-sync [:scope/init module])
    (restore/begin! {:hydrate? true})
    (restore/install-module-views! {module [:view]})
    (set! (.-innerHTML element)
          (binding [context/*server?* true context/*modules* (:specs fixture)]
            (r/render-to-string (restore/document-form form))))
    (let [server-node (.querySelector element "[data-loader-retained-target]")
          *removed (atom false)
          observer (js/MutationObserver.
                     (fn [records _]
                       (doseq [record (array-seq records)
                               removed (array-seq (.-removedNodes record))]
                         (when (or (identical? removed server-node) (.contains removed server-node))
                           (reset! *removed true)))))
          fixture' (assoc fixture :server-node server-node :*removed *removed :observer observer)]
      (.observe observer element #js {:childList true :subtree true})
      (reset! (:*retention fixture) fixture')
      (is (= "Server target" (.-textContent server-node)))
      (reset! *root (r/hydrate-root element (restore/document-form form)
                     {:on-recoverable-error #(swap! *recoveries conj (str %))}))
      (await! (support/wait-for! #(and @*shell? @*pending?) "The native ancestor did not retain pending SSR"))
      (is (false? @*committed?))
      (check-loader-retention! fixture')
      (let [reader (await! (support/mount-subscriptions!
                            {:personal [:get :state :is-personal]
                             :ready [:loader/code-ready module]
                             :active [:scope/inited? module]}))
            previous (:personal ((:values reader)))
            changed (not previous)]
        (reset! *reader reader)
        (is (true? (:active ((:values reader)))) "The source is already activated before code arrival")
        (is (not (:ready ((:values reader)))) "Activation does not imply code readiness")
        (r/dispatch [:state [:is-personal] changed])
        (await! (support/wait-for! #(= changed (:personal ((:values reader)))))))
      (await! (support/settle!))
      (is (empty? @*warnings) "An unrelated state change cannot update an uncommitted loader owner")
      (.click (.querySelector element "[data-acquire-held-loader]"))
      (await! (support/wait-for! #(contains? @(:started (:transport fixture)) module)))
      (await! (support/settle!))
      (is (empty? @*warnings) "Requesting held code does not change its actual readiness")
      fixture')))

(defn- release-held-loader-code! [{:keys [element module transport *committed? *warnings *recoveries *input *reader]
                                 :as fixture}]
  (go-promise
    ((:release! transport) module)
    (await! (support/wait-for! #(true? (:ready ((:values @*reader))))))
    (await! (support/wait-for! #(true? (:active ((:values @*reader))))))
    (await! (support/settle!))
    (await! (support/settle!))
    (is (false? @*committed?) "Independent ancestor dependency remains held after code/init readiness")
    (is (empty? @*warnings) (str "Loader readiness notified an uncommitted owner: " (pr-str @*warnings)))
    (is (empty? @*recoveries))
    (check-loader-retention! fixture)
    (is (not @(:*removed fixture)) "The real completed SSR target was never removed")
    (is (nil? (.querySelector element "[data-loader-ancestor-fallback]")))
    (let [input (.querySelector element "[data-loader-urgent-input]")
          setter (.-set (.getOwnPropertyDescriptor js/Object (.-prototype js/HTMLInputElement) "value"))]
      (.call setter input "pending section")
      (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
      (await! (support/wait-for! #(= "pending section" (.-textContent (.querySelector element "[data-loader-urgent-value]")))))
      (is (= "pending section" (.-value input) @*input) "Ordinary controlled input commits before the ancestor releases"))
    fixture))

(defn- finish-held-loader! [{:keys [element *released? release! *committed? *warnings *recoveries] :as fixture}]
  (go-promise
    (reset! *released? true)
    (release! nil)
    (await! (support/wait-for! #(deref *committed?) "The actual loaded view did not commit after the ancestor released"))
    (check-loader-retention! fixture)
    (.click (:server-node fixture))
    (await! (support/wait-for! #(= "Clicked" (.-textContent (:server-node fixture)))))
    (is (empty? @*warnings))
    (is (empty? @*recoveries))
    (is (not @(:*removed fixture)))
    (is (nil? (.querySelector element "[data-loader-ancestor-fallback]")))))

(deftest loader-readiness-retains-an-uncommitted-ssr-owner
  (async done
    (let [restore-db! (r/make-restore-fn)
          module (keyword (str "precommit-loader-" (random-uuid)))
          before-context @restore/*context
          before-validation @validation/*enabled?
          before-error (.-error js/console)
          before-warn (.-warn js/console)
          element (.createElement js/document "div")
          *root (atom nil)
          *release (atom nil)
          gate (js/Promise. (fn [resolve _] (reset! *release resolve)))
          *released? (atom false)
          *pending? (atom false)
          *committed? (atom false)
          *shell? (atom false)
          *input (r/atom "")
          *warnings (atom [])
          *recoveries (atom [])
          *retention (atom nil)
          *transport (atom nil)
          *reader (atom nil)
          specs {module {:id module :view {:view <precommit-target>}}}
          form [<held-loader-scene> module gate *released? *pending? *committed? *shell? *input]
          forward! (fn [original args]
                     (when-let [message (first (filter #(and (string? %) (string/includes? % "hasn't mounted yet")) args))]
                       (swap! *warnings conj message))
                     (.apply original js/console (to-array args)))
          fixture {:module module
                   :specs specs
                   :element element
                   :form form
                   :*root *root
                   :*released? *released?
                   :release! #(@*release %)
                   :*pending? *pending?
                   :*committed? *committed?
                   :*shell? *shell?
                   :*reader *reader
                   :*input *input
                   :*warnings *warnings
                   :*recoveries *recoveries
                   :*retention *retention}]
      (-> (js/Promise.resolve nil)
          (.then (fn []
                   (.appendChild (.-body js/document) element)
                   (reset! validation/*enabled? false)
                   (reset! *transport (transport/install! specs))
                   (set! (.-error js/console) (fn [& args] (forward! before-error args)))
                   (set! (.-warn js/console) (fn [& args] (forward! before-warn args)))
                   (prepare-held-loader! (assoc fixture :transport @*transport))))
          (.then release-held-loader-code!)
          (.then finish-held-loader!)
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when-let [retention @*retention]
                        (.disconnect (:observer retention)))
                      (when-let [reader @*reader] ((:unmount! reader)))
                      (when @*root (r/unmount @*root))
                      (reset! *released? true)
                      (@*release nil)
                      (when @*transport ((:release! @*transport) module))
                      (-> (js/Promise.resolve (get @loader/*code-loads module))
                          (.catch (fn [_] nil))
                          (.then (fn [] (support/settle!)))
                          (.finally (fn []
                                      (when @*transport ((:restore! @*transport)))
                                      (r/clear-subscription-cache!)
                                      (reset! restore/*context before-context)
                                      (reset! validation/*enabled? before-validation)
                                      (set! (.-error js/console) before-error)
                                      (set! (.-warn js/console) before-warn)
                                      (.remove element)
                                      (restore-db!)
                                      (done))))))))))

(r/defc <ssr-retry-scene> [module *committed? *mounted?]
  (let [[clicks set-clicks!] (r/use-state 0)]
    (r/use-layout-effect (fn [] (reset! *mounted? true) nil) [])
    [:r> (r/context-provider restore/readiness-context) #js {:value true}
     [:div
      [:button {:data-unrelated-loader-control true
                :on-click #(set-clicks! inc)}
       (str "Sibling " clicks)]
      (loader/component-vector {:module module :load-on :event} [*committed?])]]))

(defn- prepare-ssr-retry! [{:keys [element form module specs *root *mounted? *attempts *server-node *recoveries *phase *sibling *observer *loading]}]
  (go-promise
    (r/dispatch-sync [:init/app-db])
    (r/dispatch-sync [:scope/init module])
    (restore/begin! {:hydrate? true})
    (restore/install-module-views! {module [:view]})
    (is (restore/layout-ready?) "The fixture starts without a foreign readiness owner")
    (set! (.-innerHTML element)
          (binding [context/*server?* true context/*modules* specs]
            (r/render-to-string (restore/document-form form))))
    (reset! *server-node (.querySelector element "[data-loader-retained-target]"))
    (reset! *sibling (.querySelector element "[data-unrelated-loader-control]"))
    (let [observe! (fn [records _]
                     (doseq [record (array-seq records)
                             node (array-seq (.-addedNodes record))]
                       (when (and (= 1 (.-nodeType node))
                                  (or (.matches node ".loading-container")
                                      (.querySelector node ".loading-container")))
                         (swap! *loading conj @*phase))))]
      (reset! *observer (js/MutationObserver. observe!))
      (.observe @*observer element #js {:subtree true :childList true}))
    (reset! *root (r/hydrate-root element (restore/document-form form)
                   {:on-recoverable-error
                    (fn [error]
                      (swap! *recoveries conj
                             {:phase @*phase
                              :sibling-connected? (identical? @*sibling (.querySelector element "[data-unrelated-loader-control]"))
                              :message (str error)})
                      (.error js/console "Owned terminal module hydration recovery" error))}))
    (await! (support/wait-for! #(and @*mounted? (= 1 @*attempts))))
    (is (not (restore/layout-ready?)) "Completed SSR waits for the actual inner code commit")
    (is (nil? (.querySelector element "[role=alert]")))))

(defn- fail-ssr-retry! [{:keys [element *attempts *css-gates *server-node *phase]}]
  (go-promise
    ((:reject (get @*css-gates 1)) (js/Error. "Fixture CSS failure one"))
    (await! (support/wait-for! #(= 2 @*attempts)))
    (is (nil? (.querySelector element "[role=alert]")) "The first asset failure remains silent")
    (is (identical? @*server-node (.querySelector element "[data-loader-retained-target]"))
        "The completed SSR view survives the silent retry")
    (reset! *phase :terminal)
    ((:reject (get @*css-gates 2)) (js/Error. "Fixture CSS failure two"))
    (await! (support/wait-for! #(.querySelector element "[role=alert] button")))))

(defn- recover-ssr-retry! [{:keys [element module transport *attempts *committed? *recoveries *phase *sibling *loading]}]
  (go-promise
    (await! (support/wait-for! restore/layout-ready?))
    (is (= 2 @*attempts))
    (is (some? (.querySelector element "[role=alert]")) "The error stays local to its module")
    (is (identical? @*sibling (.querySelector element "[data-unrelated-loader-control]"))
        "Terminal module failure does not remount its unrelated sibling")
    (is (empty? @*loading) (str "Completed SSR cannot flash raw loading before its local Retry: " (pr-str @*loading)))
    (.click (.querySelector element "[data-unrelated-loader-control]"))
    (await! (support/wait-for!
              #(= "Sibling 1" (.-textContent (.querySelector element "[data-unrelated-loader-control]")))))
    (reset! *phase :retry)
    ((:release! transport) module)
    (.click (.querySelector element "[role=alert] button"))
    (await! (support/wait-for! #(and (= 3 @*attempts) @*committed?)))
    (is (= 3 @*attempts) "Native Retry reacquires the failed module")
    (is (nil? (.querySelector element "[role=alert]")))
    (is (restore/layout-ready?))
    ;; A terminal local asset error may intentionally client-render its Retry
    ;; boundary. It must not regenerate the shell or recover during pending work.
    (is (every? #(and (= :terminal (:phase %))
                      (:sibling-connected? %)
                      (string/includes? (:message %) "Hydration failed")
                      (not (string/includes? (:message %) "loading-container")))
                @*recoveries)
        (str "Terminal recovery must render local Retry directly: " (pr-str @*recoveries)))
    (is (identical? @*sibling (.querySelector element "[data-unrelated-loader-control]")))
    (.click (.querySelector element "[data-loader-retained-target]"))
    (await! (support/wait-for!
              #(= "Clicked" (.-textContent (.querySelector element "[data-loader-retained-target]")))))
    (is (= "Sibling 1" (.-textContent (.querySelector element "[data-unrelated-loader-control]"))))))

(deftest completed-ssr-failure-releases-readiness-and-retries-locally
  (async done
    (let [restore-db! (r/make-restore-fn)
          before-context @restore/*context
          before-validation @validation/*enabled?
          before-delay loader/retry-delay-ms
          module (keyword (str "ssr-retry-" (random-uuid)))
          element (.createElement js/document "div")
          *root (atom nil)
          *transport (atom nil)
          *closing? (atom false)
          *css-gates (atom {})
          *attempts (atom 0)
          *committed? (atom false)
          *mounted? (atom false)
          *server-node (atom nil)
          *recoveries (atom [])
          *phase (atom :pending)
          *sibling (atom nil)
          *observer (atom nil)
          *loading (atom [])
          specs {module {:id module :view {:view <precommit-target>}}}
          form [<ssr-retry-scene> module *committed? *mounted?]
          fixture {:element element
                   :module module
                   :specs specs
                   :form form
                   :*root *root
                   :*mounted? *mounted?
                   :*committed? *committed?
                   :*attempts *attempts
                   :*css-gates *css-gates
                   :*server-node *server-node
                   :*recoveries *recoveries
                   :*phase *phase
                   :*sibling *sibling
                   :*observer *observer
                   :*loading *loading}]
      (-> (js/Promise.resolve nil)
          (.then (fn []
                   (.appendChild (.-body js/document) element)
                   (reset! validation/*enabled? false)
                   (reset! *transport (transport/install! specs))
                   ;; Hold both real CSS failures explicitly; observation of the
                   ;; first silent failure cannot race the native retry timer.
                   (let [original-css styles/acquire!]
                     (set! styles/acquire!
                       (fn [id]
                         (if (= id module)
                           (let [attempt (swap! *attempts inc)]
                             (if (or @*closing? (> attempt 2))
                               (js/Promise.resolve nil)
                               (js/Promise.
                                 (fn [resolve reject]
                                   (swap! *css-gates assoc attempt
                                          {:resolve resolve :reject reject})))))
                           (original-css id)))))
                   (set! loader/retry-delay-ms 90)
                   (prepare-ssr-retry! fixture)))
          (.then (fn [] (fail-ssr-retry! fixture)))
          (.then (fn [] (recover-ssr-retry! (assoc fixture :transport @*transport))))
          (.catch #(is false (str %)))
          (.finally
            (fn []
              (when @*observer (.disconnect @*observer))
              (when @*root (r/unmount @*root))
              (reset! *closing? true)
              (when @*transport ((:release! @*transport) module))
              (doseq [gate (vals @*css-gates)] ((:resolve gate) nil))
              (-> (js/Promise.allSettled
                    #js [(js/Promise.resolve (get @loader/*code-loads module))
                         (js/Promise.resolve (get @loader/*loads module))])
                  (.catch (fn [_] nil))
                  (.then (fn []
                           (status/recover! [:module module])
                           (support/settle!)))
                  (.finally
                    (fn []
                      (when @*transport ((:restore! @*transport)))
                      (set! loader/retry-delay-ms before-delay)
                      (reset! validation/*enabled? before-validation)
                      (reset! restore/*context before-context)
                      (r/clear-subscription-cache!)
                      (.remove element)
                      (restore-db!)
                      (done))))))))))
