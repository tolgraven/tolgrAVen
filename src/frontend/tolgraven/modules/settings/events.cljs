(ns tolgraven.modules.settings.events
  (:require [clojure.string :as string]
            [tolgraven.react :as r]))

(r/reg-event-fx :theme/dark-mode
 (fn [{:keys [db]} [_ on?]]
   {:db (assoc-in db [:options :theme :dark-mode] on?)
    :dispatch [:html/set-attr! nil "data-theme" (if on? "dark" "light")]}))

(r/reg-event-fx :theme/colorscheme
 (fn [{:keys [db]} [_ colorscheme]]
   {:db (assoc-in db [:options :theme :colorscheme] (or colorscheme "default"))}))

(defn css-value [value rem-size]
  (if (string/ends-with? value "px")
    (str (/ (js/parseFloat value) rem-size) "rem")
    value))

(r/reg-cofx :settings/rem-size
  (fn [cofx _]
    (assoc cofx :rem-size
           (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document)))))))

(r/reg-event-fx :settings/read-css-vars
  [(r/inject-cofx :settings/rem-size)
   (r/inject-cofx :css-vars ["line-width" "line-width-vert" "section-rounded"
                             "space" "space-lg" "space-top"])]
  (fn [{:keys [db css-vars rem-size]} _]
    {:db (update-in db [:state :css-var] merge
                    (into {} (map (fn [[key value]] [key (css-value value rem-size)])) css-vars))}))
