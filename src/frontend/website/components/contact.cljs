(ns website.components.contact
  (:require-macros [website.build.site :refer [when-enabled]])
  (:require [website.component.registry]
            [website.macros :refer-macros [defc]]
            [website.react :as r]))

(defc <contact-ways> [email :- :string]
  (let [show-mail-form? @(r/subscribe [:state [:contact-form :show?]])]
    [:div
     [:div.contact-ways
      [:span [:a {:href (str "mailto:" email)
                  :style {:font-size "85%"}}
              email]]
      (when-enabled :contact nil
        [:span {:style {:color "var(--fg-6)"}}
         " | "])
      (when-enabled :contact nil
        [:button.nomargin.nopadding.noborder
       {:title "Contact us by form"
        :on-click #(r/dispatch (if show-mail-form? [:contact/close] [:contact/open]))
        :style {:color "var(--fg-5)"}}
         [:i.fas.fa-envelope]])]]))
