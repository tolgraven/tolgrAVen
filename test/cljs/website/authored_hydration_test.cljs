(ns website.authored-hydration-test
  "Exercise the release validation and selective-hydration path with delayed owned transports."
  (:require [cljs.test :refer-macros [deftest is async]]
            [shadow.lazy :as lazy]
            [website.react :as r]
            [website.db :as db]
            [website.loader :as loader]
            [website.loader.styles :as styles]
            [website.validation :as validation]
            [website.validation.runtime :as runtime]
            [website.component.restore :as restore]
            [website.render-context :as context]
            [website.components.ui.markdown :as ui]
            [website.modules.markdown-components.module :as authored]
            [website.modules.settings.module :as settings]
            [website.modules.coercion.module :as coercion]
            [website.test-support :as support]
            [website.events]
            [website.subs]))

(defn gate []
  (let [*release (atom nil)]
    {:promise (js/Promise. (fn [resolve _] (reset! *release resolve)))
     :release! (fn [value] (@*release value))}))

(defn delayed-transport! []
  (let [specs {:markdown-components authored/spec, :settings settings/spec, :coercion coercion/spec}
        before {:modules loader/modules, :load lazy/load, :css styles/acquire!, :css-ready styles/ready?
                :loads @loader/*loads, :code-loads @loader/*code-loads, :installed @loader/*installed}
        *started (atom #{})
        *code-ready (atom #{})
        *css-ready (atom #{})
        gates (into {} (for [id (keys specs)] [id {:code (gate), :css (gate)}]))
        loadables (into {} (for [[id spec] specs]
                            [id (reify lazy/ILoadable (ready? [_] (contains? @*code-ready id))
                                  IDeref (-deref [_] spec))]))
        owners (into {} (map (fn [[id loadable]] [loadable id])) loadables)]
    (doseq [*cache [loader/*loads loader/*code-loads]] (swap! *cache #(apply dissoc % (keys specs))))
    (swap! loader/*installed #(apply disj % (keys specs)))
    (set! loader/modules (merge loader/modules loadables))
    ;; Match Shadow's actual multi-arity function. Optimized static calls target
    ;; its arity properties; replacing it with a single-arity fn misses them.
    (letfn [(load! ([loadable]
                   (if-let [id (get owners loadable)]
                     (do (swap! *started conj [id :code])
                         (.then (get-in gates [id :code :promise]) (fn [spec] (swap! *code-ready conj id) spec)))
                     ((:load before) loadable)))
                  ([loadable success] (.then (load! loadable) success))
                  ([loadable success failure] (.then (load! loadable) success failure)))]
      (set! lazy/load load!))
    (set! styles/acquire!
      (fn [id]
        (if (contains? specs id)
          (do (swap! *started conj [id :css]) (get-in gates [id :css :promise]))
          ((:css before) id))))
    (set! styles/ready? (fn [id] (if (contains? specs id) (contains? @*css-ready id) ((:css-ready before) id))))
    {:started *started
     :release-code! (fn [id] ((get-in gates [id :code :release!]) (get specs id)))
     :release-css! (fn [id] (swap! *css-ready conj id) ((get-in gates [id :css :release!]) nil))
     :restore! (fn []
                 (set! loader/modules (:modules before))
                 (set! lazy/load (:load before))
                 (set! styles/acquire! (:css before))
                 (set! styles/ready? (:css-ready before))
                 (reset! loader/*loads (:loads before))
                 (reset! loader/*code-loads (:code-loads before))
                 (reset! loader/*installed (:installed before)))}))

(deftest canonical-widget-retains-node-ssr-while-owner-and-coercion-are-delayed
  (async done
    (let [before-db @r/*app-db
          before-snapshot @context/*snapshot
          before-restore @restore/*context
          before-engine @validation/*engine
          before-validation @runtime/*enabled?
          element (.createElement js/document "div")
          *root (atom nil)
          *nodes (atom nil)
          *errors (atom [])
          *completed? (atom false)
          transport (delayed-transport!)
          unchanged! (fn []
                       (is (identical? (:table @*nodes) (.querySelector element ".content-config")))
                       (is (identical? (:toggle @*nodes) (.querySelector element "[role=switch]")))
                       (is (nil? (.querySelector element ".loading-spinner,.content-component-error"))))]
      (.appendChild (.-body js/document) element)
      (is (nil? before-engine) "Release must begin without the development validation engine")
      ;; Explicit Promise stages make every deferred transport and final assertion
      ;; observable in an optimized browser test; no application IO lives here.
      (-> (js/fetch "/js/authored-config-ssr.json" #js {:cache "no-store"})
          (.then #(.json %))
          (.then (fn [payload]
                   (let [snapshot (:snapshot (js->clj payload :keywordize-keys true))
                         staging (.createElement js/document "div")
                         source (get-in snapshot [:posts 0 :text])]
                     (reset! r/*app-db (assoc db/data :content (:content snapshot)))
                     (reset! context/*snapshot snapshot)
                     (reset! runtime/*enabled? false)
                     (reset! validation/*engine nil)
                     (restore/begin! {:hydrate? true})
                     (restore/install-module-views! (:module-views snapshot))
                     (set! (.-innerHTML staging) (.-html payload))
                     (set! (.-innerHTML element) (.-outerHTML (.querySelector staging ".blog-post-text .md-rendered")))
                     (reset! *nodes {:table (.querySelector element ".content-config")
                                     :toggle (.querySelector element "[role=switch]")})
                     (is (some? (:table @*nodes)))
                     (reset! *root (r/hydrate-root element [ui/<md->div> source]
                                                   {:on-recoverable-error #(swap! *errors conj (.-message %))}))
                     (support/wait-for! #(contains? @(:started transport) [:markdown-components :code])
                                        "The authored component owner did not begin acquisition"))))
          (.then (fn [_]
                   (unchanged!)
                   ((:release-code! transport) :markdown-components)
                   ((:release-css! transport) :markdown-components)
                   (support/wait-for! #(contains? @(:started transport) [:coercion :code])
                                      "The authored owner did not acquire coercion during installation")))
          (.then (fn [_]
                   (unchanged!)
                   (is (not (validation/ready?)))
                   ((:release-code! transport) :coercion)
                   ((:release-css! transport) :coercion)
                   (support/wait-for! #(contains? @(:started transport) [:settings :code])
                                      "The canonical settings owner did not begin acquisition")))
          (.then (fn [_]
                   (unchanged!)
                   (is (validation/ready?))
                   ((:release-code! transport) :settings)
                   ((:release-css! transport) :settings)
                   (support/wait-for! #(contains? @loader/*installed :settings))))
          (.then (fn [_] (support/settle!)))
          (.then (fn [_]
                   (unchanged!)
                   (let [toggle (:toggle @*nodes)
                         checked (.getAttribute toggle "aria-checked")]
                     (.click toggle)
                     (support/wait-for! #(not= checked (.getAttribute toggle "aria-checked"))))))
          (.then (fn [_]
                   (unchanged!)
                   (is (empty? @*errors) (pr-str @*errors))
                   (reset! *completed? true)))
          (.catch #(is false (str (.-message %) " | acquired transports " (pr-str @(:started transport)))))
          (.finally (fn []
                      (is @*completed? "All owner gates and the final interactive control must complete")
                      (when @*root (r/unmount @*root))
                      (.remove element)
                      ((:restore! transport))
                      (r/clear-subscription-cache!)
                      (reset! r/*app-db before-db)
                      (reset! context/*snapshot before-snapshot)
                      (reset! restore/*context before-restore)
                      (reset! validation/*engine before-engine)
                      (reset! runtime/*enabled? before-validation)
                      (done)))))))
