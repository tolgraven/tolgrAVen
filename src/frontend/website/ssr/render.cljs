(ns website.ssr.render
  "Synchronous, isolated rendering of the ordinary Reagent tree. This adapter
   owns request-local subscriptions; it never replaces the running app's db."
  (:require [website.react :as r]
            [website.component.public-catalog :as public-catalog]
            [website.validation.runtime :as validation]
            [website.schema.app-db :as app-db]
            [website.schema.declarations :as declarations]
            [website.loader.code :as module-code]
            [website.component.restore :as restore]
            [website.render-context :as context]))

(defn module-views [modules]
  (into {}
        (for [[id spec] modules
              :let [canonical (sort (keep (fn [[identity owner]]
                                            (when (= id owner) (keyword identity)))
                                          public-catalog/owners))]]
          [id (vec (distinct (concat (keys (:view spec)) canonical)))])))

(defn export-inventory
  "JSON must retain qualified canonical keywords rather than dropping their namespace."
  [views]
  (into {} (map (fn [[id exports]]
                  [id (mapv (fn [value]
                              (if (keyword? value) (subs (str value) 1) (str value)))
                            (sort exports))])) views))

(defn html! [db form {:keys [modules href snapshot restored? interactive? rendered-views render-to-string]
                     :or {render-to-string r/render-to-string}}]
  (let [modules' (into {} (map (fn [[id spec]] [id (module-code/module-spec id spec)])) modules)]
    (when @validation/*enabled?
      (doseq [[id spec] modules']
        (validation/check! (str "render module " id)
                           (declarations/extend-module (:schema spec)) spec))
      ;; Use the available module declarations without mutating the browser's
      ;; registry during a local return capture or sharing request state in Node.
      (let [sections (reduce merge @validation/*sections (keep :db-schema (vals modules')))]
        (validation/check! "render state" (app-db/schema sections) db)))
    (let [subscribe (r/subscribe-function) *readers (atom {})
          read! (fn [args]
                  (or (get @*readers args)
                      (let [value (r/make-reaction #(deref (apply subscribe args)))]
                        (r/run value)
                        (swap! *readers assoc args value)
                        value)))]
      (binding [context/*server?* true context/*modules* modules' context/*href* href context/*rendered-views* rendered-views]
        (r/with-framework-bindings [r/*app-db (r/atom db)
                      r/*subscription-cache (atom {})
                      context/*snapshot (r/atom snapshot)
                      context/*interactive? (r/atom (boolean interactive?))
                      restore/*context (r/atom {:page (restore/page-key)
                                               :initial-document? (not restored?)
                                               :local? (boolean restored?)
                                               :hydrate? (not restored?) :back? (boolean restored?)})
                      r/dispatch (fn [_] nil)
                      r/subscribe (fn ([query] (read! [query]))
                                     ([query dynamic] (read! [query dynamic])))]
          (try (render-to-string (restore/document-form form))
               (finally (doseq [value (vals @*readers)] (r/dispose! value))
                        (r/clear-subscription-cache!))))))))
