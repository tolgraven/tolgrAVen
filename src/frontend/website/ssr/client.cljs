(ns website.ssr.client
  (:require [clojure.string :as string]
            [website.react :as r]

            [website.component.restore :as restore]
            [website.render-context :as context]
            [website.ssr.contract :as contract]
            [website.modules.main.layout :as landing]
            [website.content.contract :as content-contract]
            [website.component]))

(def *snapshot context/*snapshot)

(r/reg-event-db :page/install-public-state
  (fn [db [_ value]] (contract/merge-state db value)))

(r/reg-event-fx :page/install-metadata
  (fn [_ [_ snapshot]]
    (when-let [title (:document-title snapshot)]
      {:document/set-title title})))

(defn leave! [path]
  (let [path (first (string/split path #"\?"))]
    (when (and @*snapshot (not= path (:path @*snapshot)))
      (if (and (= :landing (some-> (:kind @*snapshot) keyword)) (landing/same-layout? (:path @*snapshot) path))
        (swap! *snapshot assoc :path path)
        (reset! *snapshot nil)))))

(defn install! []
  (when-let [element (.getElementById js/document "ssr-bootstrap")]
    (let [snapshot (-> (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true)
                       (update :content content-contract/normalize-content))]
      (when-not (and (= 3 (:renderer-version snapshot))
                     (= (.-pathname js/location) (:path snapshot))
                     (vector? (:posts snapshot)))
        (throw (js/Error. "Invalid page hydration snapshot")))
      (r/dispatch-sync [:page/install-public-state (contract/snapshot-state snapshot)])
      (reset! *snapshot snapshot)
      (restore/install-module-views! (:module-views snapshot))
      (r/dispatch-sync [:page/install-metadata snapshot])
      (reset! context/*interactive? false)
      snapshot)))

(r/defc <hydrate> [form]
  (r/use-layout-effect
   (fn []
     ;; Run after the hydration commit, before the browser's next paint. Reagent
     ;; flushes with React.flushSync, which cannot run inside a React lifecycle.
     (let [*active? (atom true)]
       (js/queueMicrotask
        (fn []
          (when @*active?
            (reset! context/*interactive? true)
            (r/flush)
            (when (and ^boolean goog.DEBUG (:hydrate? @restore/*context))
              (r/dispatch [:dev-console/hydrated]))
            (r/dispatch [:page/hydrated])
            (restore/hydrated!))))
       #(reset! *active? false))) [])
  form)
