(ns website.modules.settings.events
  (:require [clojure.string :as string]
            [website.react :as r]))

(r/reg-event-fx :theme/dark-mode
 (fn [{:keys [db]} [_ on?]]
   {:db (assoc-in db [:options :theme :dark-mode] on?)
    :dispatch [:html/set-attr! nil "data-theme" (if on? "dark" "light")]}))

(r/reg-cofx :settings/dark-mode
  (fn [cofx _]
    (let [selected (.getAttribute (.-documentElement js/document) "data-theme")]
      (assoc cofx :dark-mode
             (if (#{"dark" "light"} selected)
               (= "dark" selected)
               (.-matches (.matchMedia js/window "(prefers-color-scheme: dark)")))))))

(r/reg-event-fx :settings/read-theme
  [(r/inject-cofx :settings/dark-mode)]
  (fn [{:keys [db dark-mode]} _]
    {:db (assoc-in db [:options :theme :dark-mode] dark-mode)}))

(r/reg-event-fx :theme/toggle
  [(r/inject-cofx :settings/dark-mode)]
  (fn [{:keys [dark-mode]} _]
    {:dispatch [:theme/dark-mode (not dark-mode)]}))

(r/reg-event-fx :theme/colorscheme
 (fn [{:keys [db]} [_ colorscheme]]
   {:db (assoc-in db [:options :theme :colorscheme] (or colorscheme "default"))}))

(defn css-value
  ([value rem-size] (css-value value rem-size "rem"))
  ([value rem-size unit]
   (cond
     (and (= unit "px") (string/ends-with? value "rem"))
     (str (* (js/parseFloat value) rem-size) "px")

     (and (= unit "rem") (string/ends-with? value "px"))
     (str (/ (js/parseFloat value) rem-size) "rem")

     :else value)))

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
                    (into {} (map (fn [[key value]]
                                    [key (css-value value rem-size
                                           (if (#{"line-width" "line-width-vert"} key)
                                             "px" "rem"))])) css-vars))}))
