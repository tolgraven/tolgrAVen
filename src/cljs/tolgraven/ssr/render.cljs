(ns tolgraven.ssr.render
  "Synchronous, isolated rendering of the ordinary Reagent tree. This adapter
   owns request-local subscriptions; it never replaces the running app's db."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.server :as server]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [re-frame.subs :as subs]
            [tolgraven.component.restore :as restore]
            [tolgraven.render-context :as context]))

(defn html! [db form {:keys [modules href snapshot restored? interactive?]}]
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
