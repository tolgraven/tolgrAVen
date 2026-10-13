(ns tolgraven.component.restore
  "First-page restoration context; no loading/motion bypass without ready data."
  (:require [tolgraven.react :as r]

            [tolgraven.component.hydration :as hydration]

            [clojure.string :as string]))

(defonce hydrating-context (r/create-context false))
(defonce readiness-context (r/create-context false))
(defonce *pending-layout (atom #{}))
(defonce *layout-listeners (atom #{}))

(defn layout-ready? [] (empty? @*pending-layout))
(defn listen-layout! [callback]
  (swap! *layout-listeners conj callback)
  #(swap! *layout-listeners disj callback))

(defn use-readiness!
  "Track mounted pending code/data inside the page, never speculative renders.
   Ready and terminal error views release their blocker after the DOM commit."
  [ready?]
  (let [tracked? (r/use-context readiness-context)
        *identity (r/use-ref nil)]
    (when-not (.-current *identity) (set! (.-current *identity) (js-obj)))
    (r/use-layout-effect
      (fn []
        (if (and tracked? (not ready?))
          (let [id (.-current *identity)
                notify! #(doseq [callback @*layout-listeners] (callback))]
            (swap! *pending-layout conj id)
            (notify!)
            (fn []
              (swap! *pending-layout disj id)
              (notify!)))
          nil))
      [tracked? ready?])))

(defn document-form
  "Keep the native document's React tree slots identical in Node, local capture
   and hydration. Empty lifecycle/console slots add no markup but preserve useId."
  ([page] (document-form page nil nil))
  ([page lifecycle console] [:<> page lifecycle console]))

(defonce *context (r/atom {}))
(defn initial-hydration?
  "Capture the mount's hydration decision without subscribing its render to the
   later hydration-complete update. Call from a hook initializer, not derived UI."
  []
  (r/without-tracking #(boolean (:hydrate? @*context))))
(defn install-module-views! [views]
  (swap! *context assoc :module-views
         (into {} (map (fn [[id exports]]
                         [(keyword id) (set (map keyword exports))]) views))))
(defn rendered-view? [module view]
  (r/without-tracking #(contains? (get-in @*context [:module-views module]) view)))

(defn page-key [] (if (exists? js/location) (str (.-pathname js/location) (.-search js/location)) "/"))
(defn begin! [{:keys [hydrate? back?]}]
  (reset! *context {:hydrate? (boolean hydrate?) :back? (boolean back?)
                    :initial-document? (boolean (and hydrate? (not back?))) :page (page-key)}))
(defn skip-enter? []
  (and (= (:page @*context) (page-key)) (or (:hydrate? @*context) (:back? @*context))))
(defn- pathname [path] (first (string/split path #"\?")))
(defn document-enter? []
  ;; Query navigation retains the already-visible document and its native clock.
  (and (:page @*context)
       (= (pathname (:page @*context)) (pathname (page-key)))
       (:initial-document? @*context)))
(defn local-document? [] (boolean (:local? @*context)))
(defn direct-images?
  "History/departure images retain only their device-appropriate final candidate.
   This media policy does not change the page's SSR entrance/hydration contract."
  []
  (and (= (:page @*context) (page-key))
       (boolean (if (contains? @*context :direct-images?)
                  (:direct-images? @*context)
                  (or (:back? @*context) (:local? @*context))))))
(defn direct-images! []
  ;; The document root is outside React. Native Back can suppress lazy preview
  ;; discovery immediately while normal component rendering removes its layer.
  (when (exists? js/document)
    (.setAttribute (.-documentElement js/document) "data-image-direct" "true"))
  (swap! *context assoc :page (page-key) :direct-images? true))
(defn hydrated! []
  ;; Existing motion hooks retain their first-render decision. New components
  ;; loaded or expanded afterwards must use normal SPA entrance motion.
  (swap! *context assoc :hydrate? false))
(defn navigate! [path]
  (when-let [page (:page @*context)]
    (let [other-page? (not= (pathname path) (pathname page))]
      (when (or other-page? (not= page (page-key)))
        (when (exists? js/document)
          (.removeAttribute (.-documentElement js/document) "data-image-direct"))
        ;; Explicit false prevents returning to the initial query from reviving
        ;; the departure policy. The existing document entrance keeps its clock.
        (swap! *context assoc :direct-images? false))
      (when other-page?
        (hydration/release-all!)
        (reset! *context {})))))
(defn spa-back! []
  ;; SPA history keeps page Back motion, but is not an external document return.
  ;; Same-path query pops retain the initial document's native entrance clock.
  (when-not (document-enter?) (begin! {:back? true}))
  (swap! *context assoc :direct-images? false))
(defn back-navigation? []
  (or (= "back_forward" (some-> js/performance (.getEntriesByType "navigation") (aget 0) .-type))
      (= 2 (some-> js/performance .-navigation .-type))))
(defn resumed! [event]
  (when (.-persisted event) (begin! {:back? true})))

(defn release-history! []
  ;; Hand SPA history back to the browser before leaving, including BFCache.
  (set! (.-scrollRestoration js/history) "auto"))
