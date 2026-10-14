(ns website.components.ui.menu
  "A native disclosure for secondary controls, with caller-owned transient state."
  (:require [website.component.registry]
            [website.macros :refer-macros [defc]]))

(def options-schema
  [:map
   [:open? :boolean]
   [:on-open-change fn?]
   [:?label :string]])

(defc <menu>
  {:spec false}
  [{:keys [open? on-open-change label] :or {label "Menu"}} :- options-schema
   content :- vector?]
  [:details.disclosure-menu
   {:open open?
    :on-toggle #(on-open-change (.. % -currentTarget -open))
    :on-key-down (fn [event]
                   (when (= "Escape" (.-key event))
                     (.preventDefault event)
                     (on-open-change false)
                     (.focus (.querySelector (.-currentTarget event) "summary"))))
    :on-blur (fn [event]
               ;; A native file picker temporarily has no relatedTarget. Keep its
               ;; input mounted and the disclosure open until focus returns.
               (when-let [target (.-relatedTarget event)]
                 (when-not (.contains (.-currentTarget event) target)
                   (on-open-change false))))}
   [:summary {:aria-label label} label]
   [:div.disclosure-menu__content content]])
