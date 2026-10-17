(ns website.modules.settings.views
  (:require [website.component.registry]
            [website.macros :refer-macros [defc]]
            [website.react :as r]
            [clojure.string :as string]))

(defc <theme-switch> []
  (let [dark? @(r/subscribe [:option [:theme :dark-mode]])]
    [:button {:type "button"
              :role "switch"
              :aria-label "Dark mode"
              :aria-checked (boolean dark?)
              :on-click #(r/dispatch [:theme/toggle])}
     (if dark? "On" "Off")]))

(defc <panel> "Settings panel for theme and stuff"
  {:features [[:appear {:class "settings-motion", :viewport? false}] [:exit {:retain-layout? true}]]}
  []
  (r/use-effect (fn [] (r/dispatch [:settings/read-css-vars])
                       (r/dispatch [:settings/read-theme]) nil) [])
  (let [vars {:line-width         {:unit "px"   :min 0     :max 15}
              :line-width-vert    {:unit "px"   :min 0     :max 15}
              :section-rounded    {:unit "%"    :min 0     :max 10}
              :space              {:unit "rem"  :min 0.0   :max 4.0 :step 0.1}
              :space-lg           {:unit "rem"  :min 0.0   :max 6.0 :step 0.1}
              :space-top          {:unit "rem"  :min 0.0   :max 6.0 :step 0.1}}]
    [:div.settings-panel
     [:div.settings-panel__content
      [:div.settings-panel__body

      [:h2 [:i {:class "fa fa-cog"}] " Settings"]
      [:div
       [:span "Dark mode "]
       [<theme-switch>]]

      [:div.settings-numbers
       (doall (for [[k {:keys [unit min max step]}] vars]
         ^{:key (str "settings-input-var-" (name k))}
         [:div.settings-number
          [:input
           {:id (str (name k) "-input")
            :type :number
            :min min :max max :step step
            :value (if-let [value @(r/subscribe [:get-css-var (name k)])]
                     (str (js/parseFloat value)) "")
            :on-change #(r/dispatch [:->css-var! (name k) (-> % .-target .-value (str unit))])}]
          [:label {:for (str (name k) "-input")}
           (-> (name k)
               (string/replace "-" " ")
               (string/capitalize))]]))]

      #_[:blog posts per page incl lazy-load option]
      #_[:palette in general?
      #_[:other css vars...]
      #_[:idea to let customize as much as possible and eventually turn into a kinda interactive site-builder]]]]]))

(defc <settings>
  {:features [:error-boundary :presence]}
  []
  [:div.settings-host
   (when @(r/subscribe [:state [:settings :panel-open]])
     ^{:key :settings-panel} [<panel>])])

(def appearance-numbers
  {:line-width {:unit "px", :min 0, :max 15}
   :line-width-vert {:unit "px", :min 0, :max 15}
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
  (r/use-effect (fn [] (r/dispatch [:settings/read-css-vars])
                       (r/dispatch [:settings/read-theme])) [])
  [:section.content-config
   [:h3 (or title "Appearance")]
   [:table
    [:tbody
     [:tr [:th {:scope "row"} "Dark mode"]
      [:td [<theme-switch>]]]
     (doall (for [[key {:keys [unit min max step]}] appearance-numbers]
       ^{:key key}
       [:tr [:th {:scope "row"} (string/capitalize (string/replace (name key) "-" " "))]
        [:td [:input {:type "number"
                      :aria-label (name key)
                      :min min
                      :max max
                      :step step
                      :value (if-let [value @(r/subscribe [:get-css-var (name key)])]
                               (str (js/parseFloat value)) "")
                      :on-change #(r/dispatch [:->css-var! (name key) (str (.. % -target -value) unit)])}]]]))]]])
