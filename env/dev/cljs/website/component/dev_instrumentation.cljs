(ns website.component.dev-instrumentation
  "Development-only React profiling. Adds no DOM wrappers or application store."
  (:require [clojure.string :as string]
            [website.react :as r]

            [website.component.markup :as markup]
            [website.component.restore :as restore]
            [website.render-context :as context]
            [website.dev-console.capture :as capture]
            [website.dev.consumer :as consumer]))

(defonce *path-resolver (atom nil))
(defonce *dependencies-resolver (atom nil))
(defonce parent-context (r/create-context nil))
(defonce profiling-context (r/create-context true))

(defn without-profiling
  "Exclude the inspector host, including its ordinary deferred/module wrappers.
   Inclusive ancestor Profilers would otherwise publish the console's own commits
   back into the records that caused those commits. Keep instance metadata intact."
  [form]
  [:r> (r/context-provider profiling-context) #js {:value false} form])
(defn register-dependencies-resolver! [resolve!] (reset! *dependencies-resolver resolve!))
(defn register-path-resolver! [resolve!] (reset! *path-resolver resolve!))
(defn identity-for [definition] [(:ns definition) (:name definition)])
(defn native-root? [form]
  (and (vector? form)
       (or (string? (first form))
           (and (keyword? (first form)) (not (#{:<> :> :r> :f>} (first form)))))))

(r/defc <probe> [definition args instance-key form owner]
  (let [*id (r/use-ref nil)
        *details (r/use-ref nil)
        *identity (r/use-ref nil)
        *last-body (r/use-ref nil)
        *committed (r/use-ref false)
        [*client-commit] (r/use-state #(r/atom false))
        hydration-context? (r/use-context restore/hydrating-context)
        [hydrating?] (r/use-state #(or hydration-context? (restore/initial-hydration?)))
        _ @*client-commit
        parent (r/use-context parent-context)
        profiling? (r/use-context profiling-context)
        interactive? @context/*interactive?
        attributed? (and (native-root? form) (.-current *committed) interactive?)
        _ (when-not (.-current *id) (set! (.-current *id) (str (random-uuid))))
        qualified (string/join "/" (identity-for definition))
        path (when-let [resolve! @*path-resolver] (resolve! definition args instance-key))
        connected? @capture/*connected?
        flash @(r/subscribe [:dev-console/flash (.-current *id)])
        resolve-dependencies! (fn []
                                (when-let [resolve! @*dependencies-resolver]
                                  (try (capture/preview (resolve! definition args))
                                       (catch :default error [{:resolution-error (str error)}]))))
        dependencies (when connected? (resolve-dependencies!))]
    ;; Observe the commit without scheduling another render. A diagnostic-only
    ;; ancestor update can stall selective hydration while its lazy child waits.
    ;; Readiness/capture or the next normal render supplies SSR attributes.
    ;; Ordinary client mounts can reveal attributes immediately after commit.
    (r/use-layout-effect
      (fn []
        (set! (.-current *committed) true)
        (when-not hydrating? (reset! *client-commit true))
        nil) [])
    (when-not (.-current *identity)
      (set! (.-current *identity)
            {:instance (.-current *id)
             :component (identity-for definition)}))
    ;; Opening the inspector backfills current details; closed probes avoid
    ;; resolving dependencies on each render.
    (r/use-layout-effect
      (fn []
        ;; Backfill the committed resolver only. An abandoned render must not
        ;; replace details for the still-mounted diagnostic instance.
        (set! (.-current *details)
              (fn [] {:depends (if connected? dependencies (resolve-dependencies!))
                      :queries (capture/preview (:queries (consumer/snapshot owner)))
                      :source (get-in definition [:options :source])}))))
    (r/use-effect
      (fn []
        (capture/emit! {:kind :mount
                        :instance (.-current *id)
                        :component (identity-for definition)
                        :parent (:instance parent)
                        :parent-component (:component parent)
                        :path path
                        :resolve! #((.-current *details))
                        :key instance-key
                        :native? attributed?
                        :page (when (exists? js/location) (.-pathname js/location))})
        #(capture/emit! {:kind :unmount, :instance (.-current *id)}))
      [qualified path dependencies (:instance parent) attributed?])
    (let [form (if attributed?
                 (let [[tag attrs & children] form
                       attrs? (map? attrs)
                       attrs (if attrs? attrs {})]
                   (with-meta
                     (into [tag (cond-> (assoc attrs
                                              :data-dev-component qualified
                                              :data-dev-instance (.-current *id)
                                              :style (assoc (:style attrs) :anchor-name
                                                            (str "--dev-instance-" (.-current *id))))
                                  flash (update :class
                                          #(conj (if (vector? %) % [%])
                                                 (str "dev-component--flash-" (mod flash 2)))))]
                           (if attrs? children (rest form)))
                     (meta form)))
                 form)]
      ;; :> recursively converts props, including :value, into JS. Raw provider
      ;; props keep the diagnostic identity itself a Clojure map for consumers;
      ;; only React's outer props envelope is native.
      [:r> (r/context-provider parent-context) #js {:value (.-current *identity)}
       [:> r/profiler
        {:id qualified
         :onRender
         (when (and connected? profiling? (not= false (get-in definition [:options :profile])))
           (fn [_ phase duration base start commit]
             (when @capture/*recording?
               (let [body (consumer/snapshot owner)
                     fresh? (not= (:start body) (.-current *last-body))]
                 (when (and fresh? (:start body))
                   (set! (.-current *last-body) (:start body))
                   (capture/emit! {:kind :metadata
                                   :instance (.-current *id)
                                   :queries (capture/preview (:queries body))
                                   :source (:source body)})
                   (when (and @capture/*flash?
                              (some #(= :subscription-results (:cause %)) (:reasons body)))
                     (r/dispatch [:dev-console/flash-component (.-current *id)]))
                   (capture/emit!
                     (merge (dissoc body :values :queries)
                            {:kind :view
                             :component (identity-for definition)
                             :instance (.-current *id)})))
                 (capture/emit! {:kind :render
                                 :component (identity-for definition)
                                 :instance (.-current *id)
                                 :phase phase
                                 :duration duration
                                 :base-duration base
                                 :start start
                                 :commit commit
                                 :end (+ start duration)
                                 :reasons (if fresh? (:reasons body)
                                              [{:cause :descendant-or-profiler-update}])})))))}
        form]])))

(defn instrument
  ([definition form]
   (let [argv (r/component-argv)]
     (instrument definition form (vec (rest argv)) (:key (meta argv)))))
  ([definition form args key]
   (let [form (if (fn? form) form (markup/normalize-form form))]
     (if (or (not ^boolean goog.DEBUG)
             (string/starts-with? (:ns definition) "website.dev-console.")
             (string/starts-with? (:ns definition) "website.modules.dev-console."))
       form
       (if (fn? form)
         (fn [& args] (instrument definition (apply form args) args key))
         ;; Native metadata belongs to the probe's post-commit render, including
         ;; selectively hydrated SSR subtrees. Never modify the initial markup.
         (if context/*server?* form
             [<probe> definition args key form (r/current-component)]))))))
