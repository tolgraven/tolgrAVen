(ns tolgraven.modules.settings.events
  (:require [tolgraven.react :as rf]))

(rf/reg-event-fx :theme/dark-mode
 (fn [{:keys [db]} [_ on?]]
   {:db (assoc-in db [:options :theme :dark-mode] on?)}))

(rf/reg-event-fx :theme/colorscheme
 (fn [{:keys [db]} [_ colorscheme]]
   {:db (assoc-in db [:options :theme :colorscheme] (or colorscheme "default"))}))

(rf/reg-event-fx :settings/read-css-vars
  [(rf/inject-cofx :css-vars ["line-width" "line-width-vert" "section-rounded"
                             "space" "space-lg" "space-top"])]
  (fn [{:keys [db css-vars]} _]
    {:db (update-in db [:state :css-var] merge css-vars)}))
