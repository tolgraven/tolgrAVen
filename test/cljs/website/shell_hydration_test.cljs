(ns website.shell-hydration-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [clojure.string :as string]
            [cljs.test :refer-macros [async deftest is]]
            [re-frame.registrar :as registrar]
            [website.component.restore :as restore]
            [website.browser-resources :as resources]
            [website.modules.highlight.code-block :as code]
            [website.modules.highlight.module :as highlight]
            [website.modules.highlight.views :as highlighter]
            [website.components.shell :as shell]
            [website.events]
            [website.loader :as loader]
            [website.module-transport :as transport]
            [website.modules.user.subs]
            [website.modules.search.events]
            [website.modules.search.subs]
            [website.modules.search.views :as search]
            [website.react :as r]
            [website.render-context :as context]
            [website.subs]
            [website.test-support :as support]
            [website.validation.runtime :as validation]))

(defn react-hydration-warning? [message]
  (and (string? message)
       (or (string/includes? message "Detected a large number of updates inside startTransition")
           (string/includes? message "Can't perform a React state update on a component that hasn't mounted yet"))))

(defn observe-react-hydration-warnings! []
  (let [old-error (.-error js/console)
        old-warn (.-warn js/console)
        *messages (atom [])
        forward! (fn [original args]
                   (when-let [message (first (filter react-hydration-warning? args))]
                     (swap! *messages #(vec (take 32 (conj % message)))))
                   (.apply original js/console (to-array args)))]
    ;; Preserve ordinary subscription/SSR diagnostics and the original console
    ;; calls. Only these observed React lifecycle warnings fail this regression.
    (set! (.-error js/console) (fn [& args] (forward! old-error args)))
    (set! (.-warn js/console) (fn [& args] (forward! old-warn args)))
    {:stop! (fn []
              (try
                (is (empty? @*messages) (str "React hydration scheduling warnings: " (pr-str @*messages)))
                (finally
                  (set! (.-error js/console) old-error)
                  (set! (.-warn js/console) old-warn))))}))

(defn observe-retention! [element nodes]
  (let [*receipts (atom [])
        record! (fn [records]
                  (doseq [record (array-seq records)
                          node nodes]
                    (when (some #(or (identical? % node) (.contains % node))
                                (array-seq (.-removedNodes record)))
                      (swap! *receipts conj :removed))
                    (when (and (= "attributes" (.-type record))
                               (.contains (.-target record) node)
                               (or (= "none" (some-> record .-target .-style .-display))
                                   (re-find #"display\s*:\s*none" (or (.-oldValue record) ""))))
                      (swap! *receipts conj :hidden))))
        observer (js/MutationObserver. (fn [records _] (record! records)))
        retained! (fn [stage]
                    (record! (.takeRecords observer))
                    (is (every? #(.contains element %) nodes) (str stage " retains completed native SSR"))
                    (is (empty? @*receipts) (str stage " never removed or hid SSR: " (pr-str @*receipts))))]
    (.observe observer element #js {:childList true :subtree true :attributes true
                                   :attributeFilter #js ["style"] :attributeOldValue true})
    {:assert! retained!
     :stop! (fn [] (retained! "Before intentional unmount") (.disconnect observer))}))

(r/defc <deferred-content> [*committed?]
  (r/use-layout-effect (fn [] (reset! *committed? true) nil) [])
  [:p {:data-deferred-shell-sibling true} "Completed server content"])

(r/defc <controlled-search> []
  [:section {:data-shell-search true}
   [:output {:data-shell-query true} @(r/subscribe [:search/query])]
   [search/<box> {:id "pending-shell-search", :open? true, :inline? true}]])

(r/defc <native-shell> [module *shell-committed? *sibling-committed? & [*value search?]]
  (r/use-layout-effect (fn [] (reset! *shell-committed? true) nil) [])
  [:<>
   [shell/<header> {:menu {:work [], :personal []}}]
   (when *value [:output {:data-shell-value true} @*value])
   (when search? [<controlled-search>])
   (loader/component-vector {:module module, :load-on :immediate} [*sibling-committed?])
   [shell/<footer> [{:id "hydration", :title "Native sticky footer"}]]])

(deftest pending-ssr-sibling-does-not-delay-native-shell-visibility
  (async done
    (let [restore-db! (r/make-restore-fn)
          before-context @restore/*context
          before-interactive? @context/*interactive?
          before-validation? @validation/*enabled?
          before-flush (r/batching-flush)
          before-delay (registrar/get-handler :fx :dispatch-later)
          *search-delayed (atom [])
          style (.-style (.-documentElement js/document))
          before-css (into {} (for [property ["--header-height-current" "--footer-height-current"]]
                                [property [(.getPropertyValue style property)
                                           (.getPropertyPriority style property)]]))
          module (keyword (str "shell-hydration-sibling-" (random-uuid)))
          specs {module {:id module, :view {:view <deferred-content>}}}
          acquisition (transport/install! specs)
          element (.createElement js/document "div")
          *root (atom nil)
          *errors (atom [])
          *shell-committed? (atom false)
          *sibling-committed? (atom false)
          *value (r/atom "Initial")
          *upstream (atom nil)
          form [<native-shell> module *shell-committed? *sibling-committed? *value true]]
      (.appendChild (.-body js/document) element)
      ;; Keep unrelated Search/User fallback owners closed. Only this test's real
      ;; completed SSR module is pending; its native loader retains the gate.
      (reset! context/*interactive? false)
      (reset! validation/*enabled? false)
      (r/dispatch-sync [:init/app-db])
      (r/dispatch-sync [:search/init])
      ;; Own only Search's debounce delivery. Native query events/subscriptions
      ;; and controlled input commits remain live; unrelated delays pass through.
      (r/reg-fx :dispatch-later
        (fn [effects]
          (let [search? #(= :search/request (first (:dispatch %)))
                other (remove search? effects)]
            (swap! *search-delayed into (filter search? effects))
            (when (seq other) (before-delay other)))))
      (r/dispatch-sync [:state [:hidden] {:header false, :footer false}])
      (r/dispatch-sync [:state [:scroll] {:past-top true, :at-bottom false}])
      (restore/begin! {:hydrate? true})
      (restore/install-module-views! {module [:view]})
      (set! (.-innerHTML element)
            (binding [context/*server?* true, context/*modules* specs]
              (r/render-to-string form)))
      (let [header (.querySelector element "header")
            footer (.querySelector element "#footer-sticky")
            sibling (.querySelector element "[data-deferred-shell-sibling]")
            retention (observe-retention! element [sibling])
            warnings (observe-react-hydration-warnings!)
            hidden? #(and (.contains (.-classList header) "hide")
                          (.contains (.-classList footer) "hide"))]
        (-> (go-promise
              (is (every? some? [header footer sibling]) "Native completed server markup is present")
              (is (not (hidden?)))
              (reset! *root (r/hydrate-root element form
                             {:on-recoverable-error #(swap! *errors conj (.-message %))}))
              (reset! *upstream (r/batching-flush))
              (await! (support/wait-for! #(and @*shell-committed?
                                              (contains? @(:started acquisition) module))
                                        "Shell did not commit beside its pending SSR sibling"))
              (restore/hydrated!)
              (is (false? @*sibling-committed?))
              (is (identical? sibling (.querySelector element "[data-deferred-shell-sibling]"))
                  "Pending hydration retains completed SSR content")
              (r/dispatch-sync [:hide-header-footer true true])
              (is (true? @(r/subscribe [:state [:hidden :header]]))
                  "The real event's header state was accepted")
              (is (true? @(r/subscribe [:state [:hidden :footer]]))
                  "The real event's footer state was accepted")
              (await! (support/wait-for!
                        #(and (= "var(--space)" (.getPropertyValue style "--header-height-current"))
                              (= "calc(var(--space) + 2 * var(--line-width))"
                                 (.getPropertyValue style "--footer-height-current")))
                        "The real shell event's document effects did not run"))
              ;; Do not release the pending owner to make this assertion pass.
              ;; A failed independence check still proceeds to the positive control.
              (let [committed-before-release?
                    (await! (-> (support/wait-for! hidden? "Shell classes waited for an unrelated SSR sibling")
                                (.then (fn [_] true))
                                (.catch (fn [_] false))))]
                (is committed-before-release?
                    (str "Accepted hidden state/CSS must reach native shell classes while the module gate is closed; "
                         "header=" (.-className header) ", footer=" (.-className footer)))
                (is (false? @*sibling-committed?) "The discriminating assertion precedes sibling hydration"))
              (is (= "true" (.getAttribute footer "aria-hidden")) "Footer becomes aria-hidden before parser release")
              (is (.hasAttribute footer "inert") "Footer becomes inert before parser release")
              ((:assert! retention) "Native hide committed beside pending SSR")
              (reset! *value "Ordinary ratom update")
              (await! (support/wait-for! #(= "Ordinary ratom update"
                                             (.-textContent (.querySelector element "[data-shell-value]")))))
              ((:assert! retention) "Ordinary Reagent parent update committed beside pending SSR")
              ;; Same native value setter/input-event boundary as search_test.
              ;; This is a mounted controlled-input regression, not trusted typing.
              (let [setter (.-set (.getOwnPropertyDescriptor js/Object (.-prototype js/HTMLInputElement) "value"))]
                (doseq [query ["u" "ui" "ui " "ui p" "ui pa" "ui par" "ui pari" "ui parit" "ui parity"
                              "" "c" "co" "col" "cold" "cold "]]
                  (let [input (.querySelector element "#pending-shell-search")]
                    (.call setter input query)
                    (.setSelectionRange input (count query) (count query))
                    (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
                    (is (= query (.-value input))
                        "React must not restore a stale controlled value after the input event")
                    (await! (support/wait-for!
                              #(and (= query (some-> (.querySelector element "[data-shell-query]") .-textContent))
                                    (= query (some-> (.querySelector element "[data-shell-search] .styled-query-text") .-textContent)))
                              "Search query and visible text did not commit beside pending SSR"))
                    (is (= query (some-> (.querySelector element "#pending-shell-search") .-value))
                        "DOM value, query subscription and styled text stay synchronized")
                    (is (false? @*sibling-committed?) "Every input check precedes SSR gate release")
                    ((:assert! retention) "Controlled Search input committed beside pending SSR"))))
              (is (seq @*search-delayed) "Real query events requested the registered debounce effect")
              (r/dispatch-sync [:hide-header-footer false false])
              (await! (support/wait-for! #(and (not (hidden?))
                                              (not (.contains (.-classList header) "hide"))
                                              (not (.contains (.-classList footer) "hide"))
                                              (not (.hasAttribute footer "inert"))
                                              (nil? (.getAttribute footer "aria-hidden")))
                                        "Native show event did not restore interactive shell before release"))
              ((:assert! retention) "Native show committed beside pending SSR")
              (is (false? @*sibling-committed?) "Hide/show and ratom checks keep the gate closed")
              (r/dispatch-sync [:hide-header-footer true true])
              (await! (support/wait-for! #(and (hidden?) (.hasAttribute footer "inert"))))
              ((:release! acquisition) module)
              (await! (support/wait-for! #(and @*sibling-committed? (hidden?)
                                              (nil? (.querySelector element "template[data-module-boundary]")))
                                        "Releasing this module did not complete native hydration"))
              ((:assert! retention) "Native deferred content committed")
              (is (identical? @*upstream (r/batching-flush)) "Completion restores the upstream batching callback")
              (is (= "true" (.getAttribute footer "aria-hidden")))
              (is (.hasAttribute footer "inert"))
              (is (identical? header (.querySelector element "header"))))
            (.then (fn [_]
                     (is (identical? footer (.querySelector element "#footer-sticky")))
                     (is (identical? sibling (.querySelector element "[data-deferred-shell-sibling]")))
                     (is (empty? @*errors) (pr-str @*errors))))
            (.catch #(is false (str %)))
            (.finally (fn []
                        ((:stop! retention))
                        (when @*root (r/unmount @*root))
                        ((:release! acquisition) module)
                        (-> (js/Promise.all #js [(get @loader/*code-loads module) (get @loader/*loads module)])
                            (.catch (fn [_] nil))
                            (.then (fn [_] (support/settle!)))
                            (.then (fn [_]
                                     (.remove element)
                                     ((:restore! acquisition))
                                     (r/reg-fx :dispatch-later before-delay)
                                     (r/clear-subscription-cache!)
                                     (restore-db!)
                                     (reset! restore/*context before-context)
                                     (reset! context/*interactive? before-interactive?)
                                     (reset! validation/*enabled? before-validation?)
                                     (r/set-batching-flush! before-flush)
                                     (doseq [[property [value priority]] before-css]
                                       (if (seq value)
                                         (.setProperty style property value priority)
                                         (.removeProperty style property)))
                                     ((:stop! warnings))
                                     (done)))))))))))

(r/defc <owned-content> [id *commits]
  (r/use-layout-effect (fn [] (swap! *commits conj id) nil) [])
  [:p {:data-owned-module (name id)} "Completed owned SSR"])

(r/defc <observed-formatter> [*commits args]
  (r/use-layout-effect (fn [] (swap! *commits conj (first args)) nil) [])
  (into [highlighter/<formatted-code>] args))

(r/defc <overlapping-consumers> [ids *value *show-code? *commits]
  [:article
   [:output {:data-overlap-value true} @*value]
   (for [id ids]
     (with-meta (loader/component-vector {:module id :load-on :immediate} [id *commits])
       {:key id}))
   (when @*show-code?
     [:section {:data-owned-code "removed"}
      [code/<code-block> "(dec 2)" :language "clojure" :copy? false]])
   [:section {:data-owned-code "retained"}
    [code/<code-block> "(inc 1)" :language "clojure" :copy? false]]])

(r/defc <next-document> [module *value *commits]
  [:article
   [:output {:data-next-value true} @*value]
   (loader/component-vector {:module module :load-on :immediate} [module *commits])])

(deftest real-loader-and-formatter-overlap-unmount-and-navigation-keep-independent-ownership
  (async done
    (let [restore-db! (r/make-restore-fn)
          before-context @restore/*context
          before-interactive? @context/*interactive?
          before-validation? @validation/*enabled?
          before-flush (r/batching-flush)
          old-after-page resources/after-page!
          ids (mapv #(keyword (str "owned-" % "-" (random-uuid))) ["first" "second" "next"])
          [first-id second-id next-id] ids
          *commits (atom [])
          *formatter-commits (atom [])
          *after-page (atom [])
          *value (r/atom "Initial")
          *show-code? (r/atom true)
          *next-value (r/atom "Next initial")
          *errors (atom [])
          *phase (atom :setup)
          *old-root (atom nil)
          *next-root (atom nil)
          *retention (atom nil)
          *next-retention (atom nil)
          *upstream (atom nil)
          element (.createElement js/document "div")
          next-element (.createElement js/document "div")
          specs (assoc (into {} (map (fn [id] [id {:id id :view {:view <owned-content>}}]) ids))
                       :highlight (assoc-in highlight/spec [:view :formatted-code]
                                            (fn [& args] [<observed-formatter> *formatter-commits args])))
          acquisition (transport/install! specs)
          form [<overlapping-consumers> [first-id second-id] *value *show-code? *commits]
          next-form [<next-document> next-id *next-value *commits]]
      (.appendChild (.-body js/document) element)
      (.appendChild (.-body js/document) next-element)
      (reset! context/*interactive? false)
      (reset! validation/*enabled? false)
      (r/dispatch-sync [:init/app-db])
      (set! resources/after-page!
        (fn [release!]
          (let [*cancelled? (atom false)]
            (swap! *after-page conj {:release! release! :cancelled? *cancelled?})
            #(reset! *cancelled? true))))
      (restore/begin! {:hydrate? true})
      (restore/install-module-views! {first-id [:view] second-id [:view] :highlight [:formatted-code]})
      (set! (.-innerHTML element)
            (binding [context/*server?* true context/*modules* specs] (r/render-to-string form)))
      (let [first-node (.querySelector element (str "[data-owned-module='" (name first-id) "']"))
            second-node (.querySelector element (str "[data-owned-module='" (name second-id) "']"))
            code-node (.querySelector element "[data-owned-code='retained'] pre span[style]")
            warnings (observe-react-hydration-warnings!)]
        (-> (go-promise
              (is (every? some? [first-node second-node code-node]))
              (reset! *retention (observe-retention! element [first-node second-node code-node]))
              (reset! *old-root (r/hydrate-root element form
                                 {:on-recoverable-error #(swap! *errors conj (.-message %))}))
              ;; Capture upstream before any lease layout commits. Do not assert
              ;; callback identity while actual SSR consumers are still pending.
              (reset! *upstream (r/batching-flush))
              (await! (support/wait-for! #(and (contains? @(:started acquisition) first-id)
                                              (contains? @(:started acquisition) second-id)
                                              (= 2 (count @*after-page)))))
              (restore/hydrated!)
              (reset! *phase :formatter-unmount)
              (reset! *show-code? false)
              (await! (support/wait-for! #(and (nil? (.querySelector element "[data-owned-code='removed']"))
                                              (= 1 (count (filter (comp deref :cancelled?) @*after-page))))))
              (is (empty? @*formatter-commits) "Unmount cancelled one actual pending formatter before its inner commit")
              (reset! *value "After formatter unmount")
              (await! (support/wait-for! #(= "After formatter unmount" (.-textContent (.querySelector element "output")))))
              ((:assert! @*retention) "Unrelated pending formatter unmount")
              (reset! *phase :first-loader-completion)
              ((:release! acquisition) first-id)
              (await! (support/wait-for! #(some #{first-id} @*commits)))
              (is (not (some #{second-id} @*commits)))
              (is (empty? @*formatter-commits))
              (reset! *value "After first loader")
              (await! (support/wait-for! #(= "After first loader" (.-textContent (.querySelector element "output")))))
              ((:assert! @*retention) "One loader completed beside another loader and formatter")
              ;; Deliver even the cancelled callback to model work already queued
              ;; before cleanup; no abandoned formatter may mount or steal a lease.
              (reset! *phase :formatter-completion)
              (doseq [{:keys [release!]} @*after-page] (release!))
              ((:release! acquisition) :highlight)
              (await! (support/wait-for! #(some #{"(inc 1)"} @*formatter-commits)))
              (is (not (some #{"(dec 2)"} @*formatter-commits)))
              (is (not (some #{second-id} @*commits)))
              (reset! *value "After formatter commit")
              (await! (support/wait-for! #(= "After formatter commit" (.-textContent (.querySelector element "output")))))
              ((:assert! @*retention) "Formatter completion leaves the other SSR loader protected")
              (reset! *phase :navigation)
              (restore/navigate! "/other-hydration-document")
              (is (identical? @*upstream (r/batching-flush)) "Navigation releases the departing document's adapter")
              ;; A real second hydrated root represents the next document. The
              ;; abandoned root stays alive only until this overlap is observed.
              (restore/begin! {:hydrate? true})
              (restore/install-module-views! {next-id [:view]})
              (set! (.-innerHTML next-element)
                    (binding [context/*server?* true context/*modules* specs] (r/render-to-string next-form)))
              (let [next-node (.querySelector next-element "[data-owned-module]")]
                (is (some? next-node))
                (reset! *next-retention (observe-retention! next-element [next-node]))
                (reset! *phase :next-document)
                (reset! *next-root (r/hydrate-root next-element next-form
                                    {:on-recoverable-error #(swap! *errors conj (.-message %))}))
                (reset! *upstream (r/batching-flush))
                (await! (support/wait-for! #(contains? @(:started acquisition) next-id)))
                (restore/hydrated!)
                (reset! *phase :stale-old-cleanup)
                ((:stop! @*retention))
                (reset! *retention nil)
                (r/unmount @*old-root)
                (reset! *old-root nil)
                ((:release! acquisition) second-id)
                (await! (support/wait-for! #(contains? @loader/*installed second-id)))
                (await! (support/settle!))
                (is (not (some #{second-id} @*commits)) "Late completion cannot commit the unmounted old view")
                (reset! *next-value "After stale old cleanup")
                (await! (support/wait-for! #(= "After stale old cleanup" (.-textContent (.querySelector next-element "output")))))
                ((:assert! @*next-retention) "Old cleanup and completion cannot release a new document's owner")
                (reset! *phase :next-completion)
                ((:release! acquisition) next-id)
                (await! (support/wait-for! #(and (some #{next-id} @*commits)
                                                (nil? (.querySelector next-element "template[data-module-boundary]")))))
                ((:assert! @*next-retention) "Next actual SSR owner completed")
                (is (identical? @*upstream (r/batching-flush)) "The final consumer restores upstream batching"))
              (is (empty? @*errors) (pr-str @*errors)))
            (.catch #(is false
                         (str % " | " (pr-str {:phase @*phase
                                                :commits @*commits
                                                :formatter-commits @*formatter-commits
                                                :acquisitions @(:started acquisition)}))))
            (.finally
              (fn []
                (when @*retention ((:stop! @*retention)))
                (when @*next-retention ((:stop! @*next-retention)))
                (when @*old-root (r/unmount @*old-root))
                (when @*next-root (r/unmount @*next-root))
                (is (every? (comp deref :cancelled?) @*after-page)
                    "Unmount cancelled every actual formatter's after-page callback")
                (doseq [{:keys [release!]} @*after-page] (release!))
                (doseq [id (keys specs)] ((:release! acquisition) id))
                ;; Gate delivery queues make-formatter's acquire-code! in a
                ;; Promise callback. Snapshot only after those acquisitions start.
                (-> (js/Promise.resolve nil)
                    (.then (fn [_]
                             (js/Promise.all
                               (into-array (keep identity (mapcat #(vector (get @loader/*code-loads %) (get @loader/*loads %)) (keys specs)))))))
                    (.catch (fn [_] nil))
                    (.then (fn [_] (support/settle!)))
                    (.then (fn [_]
                             (.remove element)
                             (.remove next-element)
                             ((:restore! acquisition))
                             (set! resources/after-page! old-after-page)
                             (r/clear-subscription-cache!)
                             (restore-db!)
                             (reset! restore/*context before-context)
                             (reset! context/*interactive? before-interactive?)
                             (reset! validation/*enabled? before-validation?)
                             (r/set-batching-flush! before-flush)
                             ((:stop! warnings))
                             (done)))))))))))

(r/defc <resuming-formatter> [*source *value]
  [:article
   [:output {:data-resume-source true} @*source]
   [:output {:data-resume-value true} @*value]
   [code/<code-block> @*source :language "clojure" :copy? false]])

(deftest persisted-resume-retains-formatted-ssr-through-updates-until-ready
  (async done
    (let [restore-db! (r/make-restore-fn)
          before-context @restore/*context
          before-interactive? @context/*interactive?
          before-validation? @validation/*enabled?
          before-flush (r/batching-flush)
          old-after-page resources/after-page!
          *source (r/atom "(inc 1)")
          *value (r/atom "Initial")
          *commits (atom [])
          *errors (atom [])
          *release (atom nil)
          *cancelled? (atom false)
          *root (atom nil)
          *upstream (atom nil)
          element (.createElement js/document "div")
          specs {:highlight (assoc-in highlight/spec [:view :formatted-code]
                                      (fn [& args] [<observed-formatter> *commits args]))}
          acquisition (transport/install! specs)
          form [<resuming-formatter> *source *value]]
      (.appendChild (.-body js/document) element)
      (reset! context/*interactive? false)
      (reset! validation/*enabled? false)
      (r/dispatch-sync [:init/app-db])
      (set! resources/after-page!
        (fn [release!]
          (reset! *release release!)
          #(reset! *cancelled? true)))
      (restore/begin! {:hydrate? true})
      (restore/install-module-views! {:highlight [:formatted-code]})
      (set! (.-innerHTML element)
            (binding [context/*server?* true context/*modules* specs] (r/render-to-string form)))
      (let [token (.querySelector element "pre span[style]")
            pre (.querySelector element "pre")
            original-text (some-> pre .-textContent)
            retention (observe-retention! element [token])
            warnings (observe-react-hydration-warnings!)]
        (-> (go-promise
              (is (every? some? [token pre]))
              (reset! *root (r/hydrate-root element form
                             {:on-recoverable-error #(swap! *errors conj (.-message %))}))
              (reset! *upstream (r/batching-flush))
              (await! (support/wait-for! #(deref *release)))
              (restore/hydrated!)
              ;; Actual pageshow policy drops the inventory, while this existing
              ;; completed SSR formatter is still awaiting its after-page gate.
              (restore/resumed! #js {:persisted true})
              (is (not (restore/rendered-view? :highlight :formatted-code)))
              (reset! *source "(inc 2)")
              (await! (support/wait-for! #(= "(inc 2)" (.-textContent (.querySelector element "[data-resume-source]")))))
              (reset! *value "Ordinary update after resume")
              (await! (support/wait-for! #(= "Ordinary update after resume"
                                             (.-textContent (.querySelector element "[data-resume-value]")))))
              ((:assert! retention) "Persisted resume and changed formatter props while code stays pending")
              (is (empty? @*commits) "No formatter inner commit was used to make the shell update pass")
              (is (not (contains? @(:started acquisition) :highlight)) "After-page code acquisition stays held")
              (is (= original-text (.-textContent pre)) "Pending prop changes retain the completed server token tree")
              (@*release)
              ((:release! acquisition) :highlight)
              ;; React hydrates the retained server element before committing
              ;; the deferred prop update. Observe the painted latest text too;
              ;; requiring it in the first hydration commit contradicts retention.
              (await! (support/wait-for!
                        #(and (= ["(inc 1)"] @*commits)
                              (= "(inc 2)" (.-textContent pre)))
                        "Resumed formatter hydrates once and applies the latest source"))
              ((:assert! retention) "Native formatter completed with latest props after resume")
              (is (identical? @*upstream (r/batching-flush)) "Completion leaves upstream batching intact")
              (is (empty? @*errors) (pr-str @*errors)))
            (.catch #(is false (str %)))
            (.finally
              (fn []
                ((:stop! retention))
                (when @*root (r/unmount @*root))
                (is @*cancelled? "Unmount cancels the owned after-page callback")
                (when @*release (@*release))
                ((:release! acquisition) :highlight)
                ;; Formatter acquisition starts in the gate's Promise callback.
                ;; Collect its actual promises after that callback can run.
                (-> (js/Promise.resolve nil)
                    (.then (fn [_]
                             (js/Promise.all #js [(get @loader/*code-loads :highlight)
                                                  (get @loader/*loads :highlight)])))
                    (.catch (fn [_] nil))
                    (.then (fn [_] (support/settle!)))
                    (.then (fn [_]
                             (.remove element)
                             ((:restore! acquisition))
                             (set! resources/after-page! old-after-page)
                             (r/clear-subscription-cache!)
                             (restore-db!)
                             (reset! restore/*context before-context)
                             (reset! context/*interactive? before-interactive?)
                             (reset! validation/*enabled? before-validation?)
                             (r/set-batching-flush! before-flush)
                             ((:stop! warnings))
                             (done)))))))))))
