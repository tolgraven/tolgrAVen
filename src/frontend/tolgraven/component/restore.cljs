(ns tolgraven.component.restore
  "First-page restoration context; no loading/motion bypass without ready data."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [tolgraven.component.hydration :as hydration]
            [tolgraven.react :as rf]
            [clojure.string :as string]))

(defonce hydrating-context (rf/create-context false))
(defonce readiness-context (rf/create-context false))
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
  (let [tracked? (rf/use-context readiness-context)
        *identity (rf/use-ref nil)]
    (when-not (.-current *identity) (set! (.-current *identity) (js-obj)))
    (rf/use-layout-effect
      (fn []
        (if (and tracked? (not ready?))
          (let [id (.-current *identity)
                notify! #(doseq [callback @*layout-listeners] (callback))]
            (swap! *pending-layout conj id)
            (notify!)
            (fn []
              (swap! *pending-layout disj id)
              (notify!)))
          js/undefined))
      #js [tracked? ready?])))

(defonce *context (r/atom {}))
(defn initial-hydration?
  "Capture the mount's hydration decision without subscribing its render to the
   later hydration-complete update. Call from a hook initializer, not derived UI."
  []
  (binding [ratom/*ratom-context* nil]
    (boolean (:hydrate? @*context))))
(defn install-module-views! [views]
  (swap! *context assoc :module-views
         (into {} (map (fn [[id exports]]
                         [(keyword id) (set (map keyword exports))]) views))))
(defn rendered-view? [module view]
  (binding [ratom/*ratom-context* nil]
    (contains? (get-in @*context [:module-views module]) view)))

(defn page-key [] (if (exists? js/location) (str (.-pathname js/location) (.-search js/location)) "/"))
(defn begin! [{:keys [hydrate? back?]}]
  (reset! *context {:hydrate? (boolean hydrate?) :back? (boolean back?)
                    :initial-document? (boolean (and hydrate? (not back?))) :page (page-key)}))
(defn skip-enter? []
  (and (= (:page @*context) (page-key)) (or (:hydrate? @*context) (:back? @*context))))
(defn document-enter? []
  (and (= (:page @*context) (page-key)) (:initial-document? @*context)))
(defn local-document? [] (boolean (:local? @*context)))
(defn hydrated! []
  ;; Existing motion hooks retain their first-render decision. New components
  ;; loaded or expanded afterwards must use normal SPA entrance motion.
  (swap! *context assoc :hydrate? false))
(defn navigate! [path]
  (when (and (:page @*context)
             (not= (first (string/split path #"\?"))
                   (first (string/split (:page @*context) #"\?"))))
    (hydration/release-all!)
    (reset! *context {})))
(defn back-navigation? []
  (or (= "back_forward" (some-> js/performance (.getEntriesByType "navigation") (aget 0) .-type))
      (= 2 (some-> js/performance .-navigation .-type))))
(defn resumed! [event]
  (when (.-persisted event) (begin! {:back? true})))

(defn release-history! []
  ;; Hand SPA history back to the browser before leaving, including BFCache.
  (set! (.-scrollRestoration js/history) "auto"))
