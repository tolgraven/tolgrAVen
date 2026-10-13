(ns tolgraven.modules.settings.views
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [clojure.string :as string]))

(defc <settings> "Settings panel for theme and stuff"
  {:features [:error-boundary]}
  []
  (rf/use-effect (fn [] (rf/dispatch [:settings/read-css-vars]) nil) [])
  (let [open? @(rf/subscribe [:state [:settings :panel-open]])
        vars {:line-width         {:unit "rem"  :min 0     :max 1 :step 0.0625}
              :line-width-vert    {:unit "rem"  :min 0     :max 1 :step 0.0625}
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

(def appearance-numbers
  {:line-width {:unit "rem", :min 0, :max 1, :step 0.0625}
   :line-width-vert {:unit "rem", :min 0, :max 1, :step 0.0625}
   :section-rounded {:unit "%", :min 0, :max 10, :step 1}
   :space {:unit "rem", :min 0, :max 4, :step 0.1}
   :space-lg {:unit "rem", :min 0, :max 6, :step 0.1}
   :space-top {:unit "rem", :min 0, :max 6, :step 0.1}})

(defc <config-table>
  "Embed the reader's actual theme and appearance controls. The light/dark switch updates
   the document theme through the existing event; spacing, line width and rounding
   use the same options and CSS-variable effects as the ordinary settings panel.
   Title is optional. Preferences belong to the reader and do not change content."
  {:spec-schema [:map [:?title :string]]}
  [{:keys [title]}]
  (rf/use-effect (fn [] (rf/dispatch [:settings/read-css-vars])) [])
  (let [theme @(rf/subscribe [:option [:theme]])]
    [:section.content-config
     [:h3 (or title "Appearance")]
     [:table
      [:tbody
       [:tr [:th {:scope "row"} "Dark mode"]
        [:td [:button {:type "button"
                       :role "switch"
                       :aria-label "Dark mode"
                       :aria-checked (boolean (:dark-mode theme))
                       :on-click #(rf/dispatch [:theme/dark-mode (not (:dark-mode theme))])}
              (if (:dark-mode theme) "On" "Off")]]]
       (doall (for [[key {:keys [unit min max step]}] appearance-numbers]
         ^{:key key}
         [:tr [:th {:scope "row"} (string/capitalize (string/replace (name key) "-" " "))]
          [:td [:input {:type "number"
                        :aria-label (name key)
                        :min min
                        :max max
                        :step step
                        :value (if-let [value @(rf/subscribe [:get-css-var (name key)])]
                                 (str (js/parseFloat value)) "")
                        :on-change #(rf/dispatch [:->css-var! (name key) (str (.. % -target -value) unit)])}]]]))]]]))
