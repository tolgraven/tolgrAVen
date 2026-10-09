(ns tolgraven.ssr.render
  "Synchronous, isolated rendering of the ordinary Reagent tree. This adapter
   owns request-local subscriptions; it never replaces the running app's db."
  (:require [reagent.core :as r]
            [tolgraven.validation.runtime :as validation]
            [tolgraven.schema.app-db :as app-db]
            [tolgraven.schema.declarations :as declarations]
            [reagent.ratom :as ratom]
            [reagent.dom.server :as server]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [re-frame.subs :as subs]
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
  (let [subscribe rf/subscribe *readers (atom {})
        read! (fn [args]
                (or (get @*readers args)
                    (let [value (ratom/make-reaction #(deref (apply subscribe args)))]
                      (ratom/run value)
                      (swap! *readers assoc args value)
                      value)))]
    (binding [context/*server?* true context/*modules* modules context/*href* href]
      (with-redefs [rfdb/app-db (r/atom db)
                    subs/query->reaction (atom {})
                    context/*snapshot (r/atom snapshot)
                    context/*interactive? (r/atom (boolean interactive?))
                    restore/*context (r/atom {:page (restore/page-key)
                                             :initial-document? (not restored?)
                                             :local? (boolean restored?)
                                             :hydrate? (not restored?) :back? (boolean restored?)})
                    rf/dispatch (fn [_] nil)
                    rf/subscribe (fn ([query] (read! [query]))
                                   ([query dynamic] (read! [query dynamic])))]
        (try (server/render-to-string form)
             (finally (doseq [value (vals @*readers)] (ratom/dispose! value))
                      (rf/clear-subscription-cache!)))))))
