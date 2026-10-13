(ns tolgraven.ssr.render
  "Synchronous, isolated rendering of the ordinary Reagent tree. This adapter
   owns request-local subscriptions; it never replaces the running app's db."
  (:require [tolgraven.react :as r]
            [tolgraven.validation.runtime :as validation]
            [tolgraven.schema.app-db :as app-db]
            [tolgraven.schema.declarations :as declarations]





            [tolgraven.component.restore :as restore]
            [tolgraven.render-context :as context]))

(defn module-views [modules]
  (into {} (map (fn [[id spec]] [id (vec (keys (:view spec)))]) modules)))

(defn html! [db form {:keys [modules href snapshot restored? interactive?]}]
  (when @validation/*enabled?
    (doseq [[id spec] modules]
      (validation/check! (str "render module " id)
                         (declarations/extend-module (:schema spec)) spec))
    ;; Use the available module declarations without mutating the browser's
    ;; registry during a local return capture or sharing request state in Node.
    (let [sections (reduce merge @validation/*sections (keep :db-schema (vals modules)))]
      (validation/check! "render state" (app-db/schema sections) db)))
  (let [subscribe (r/subscribe-function) *readers (atom {})
        read! (fn [args]
                (or (get @*readers args)
                    (let [value (r/make-reaction #(deref (apply subscribe args)))]
                      (r/run value)
                      (swap! *readers assoc args value)
                      value)))]
    (binding [context/*server?* true context/*modules* modules context/*href* href]
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
        (try (r/render-to-string form)
             (finally (doseq [value (vals @*readers)] (r/dispose! value))
                      (r/clear-subscription-cache!)))))))
