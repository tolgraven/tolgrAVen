(ns website.components.ui.controls
  (:require [website.component.registry]
            [website.components.icon :as icon]
            [website.react :as r]
            [website.macros :as m :include-macros true]))


(m/defc <button> "Pass text and id, plus either link anchor or action..."
  [text id & {:keys [type bg-div-class link action disabled?]
              :or   {type "button" }}]
  [:button {:id (str "btn-" (name id)) :type type :on-click action :disabled disabled?}
   (when bg-div-class [:div {:class bg-div-class}])
   [:label {:for (str "btn-" (name id))}
    (if link
      [:a {:href link} text]
      text)]])


(m/defc <toggle-for-path> "Like below but supports attrs + path. I mean could just check if first arg is map anyways but yeah"
  ([model-path])
  ([attrs model-path]))

(m/defc <toggle> "A nice little (but not native checkbox little) toggle"
 ([model-path label]
  (let [on-change #(r/dispatch [:set model-path %])
        model (r/subscribe (into [:get] model-path))]
  [<toggle> {} model on-change label]))
 ([model on-change label]
  (<toggle> {} model on-change label))
 ([attrs model on-change label]
  (let []
   [:label.toggle-switch
      attrs
    [:input {:type :checkbox :default-checked @model
             :on-click (fn [e] ; (.preventDefault e) ;broke it! :O what
                 (on-change (not @model)))}]
    [:span.toggle-slider]
    [:div.toggle-label label]
    ])))

(m/defc <burger> "Toggle main menu. Entire point vs css is shove everything below down by extra height..."
  ([id model on-change]
   [:label.burger
    {:for id}
    [:input.burger-check
     {:id id :type :checkbox
      :default-checked @model
      :on-click (fn [e] ; (.preventDefault e) ;broke it! :O what
                  (on-change (not @model)))}]]))

(m/defc <material-toggle>
 [model-path [on-state off-state & [prefix]]]
 (let [model (r/subscribe (into [:get] model-path))]
  [icon/<icon> "fa" (str (when prefix (str prefix "-")) (if @model on-state off-state))
   {:style {:margin "0.1em 0.2em"}
    :on-click #(r/dispatch [:toggle model-path])}]))

(m/defc <minimize> [model-path]
 [<material-toggle>
  (into model-path [:minimized])
  ["maximize" "minimize" "window"]])

(m/defc <close> [on-click]
 [:button.close-btn.noborder
  {:on-click on-click :aria-label "Close"}
  [:i.fa.fa-times]])


(m/defc <input-toggle> "Don't forget to put ze label - only was sep in first place due to css bs?"
  [id checked-path & {:keys [class label]}]
  (let [checked? @(r/subscribe checked-path)]
   [:input ;.toggle
    {:id id
     :class class ;must be outside header or breaks...
     :type "checkbox"
     :default-checked checked?
     :on-click (fn []
                 (r/dispatch (into checked-path [(not checked?)])))}]))
