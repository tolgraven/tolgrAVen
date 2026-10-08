(ns tolgraven.modules.settings.views
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [clojure.string :as string]))

(defc <settings> "Settings panel for theme and stuff"
  {:features [:error-boundary]}
  []
  (rf/use-effect (fn [] (rf/dispatch [:settings/read-css-vars]) js/undefined) #js [])
  (let [open? @(rf/subscribe [:state [:settings :panel-open]])
        vars {:line-width         {:unit "px"   :min 0     :max 15}
              :line-width-vert    {:unit "px"   :min 0     :max 15}
              :section-rounded    {:unit "%"    :min 0     :max 10}
              :space              {:unit "rem"  :min 0.0   :max 4.0 :step 0.1}
              :space-lg           {:unit "rem"  :min 0.0   :max 6.0 :step 0.1}
              :space-top          {:unit "rem"  :min 0.0   :max 6.0 :step 0.1}}]
    [:div.settings-panel
     {:class (when open? "opened")
      :style {:position :sticky }}

     [:h2 [:i {:class "fa fa-cog"}] " Settings"]
     [:div
      [:button {:on-click #(rf/dispatch [:html/set-attr! nil "data-theme" "light"])}
       "Light/dark"]]

     [:div.settings-numbers
      (doall (for [[k {:keys [unit min max step]}] vars]
        ^{:key (str "settings-input-var-" (name k))}
        [:div.settings-number
         [:input
          {:id (str (name k) "-input")
           :type :number
           :min min :max max :step step
           :value (if-let [value @(rf/subscribe [:get-css-var (name k)])]
                    (str (js/parseFloat value)) "")
           :on-change #(rf/dispatch [:->css-var! (name k) (-> % .-target .-value (str unit))])}]
         [:label {:for (str (name k) "-input")}
          (-> (name k)
              (string/replace "-" " ")
              (string/capitalize))]]))]

     #_[:blog posts per page incl lazy-load option]
     #_[:palette in general?
     #_[:other css vars...]
     #_[:idea to let customize as much as possible and eventually turn into a kinda interactive site-builder]]]))
