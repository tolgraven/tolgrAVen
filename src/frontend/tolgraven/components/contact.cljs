(ns tolgraven.components.contact
  (:require-macros [tolgraven.build.site :refer [when-enabled]])
  (:require [tolgraven.component.registry]
            [tolgraven.macros :as m :refer-macros [defc]]
            [tolgraven.react :as rf]
            [tolgraven.loader]))

(defc <contact-popup>
  "One page-shell owner for the popup shared by every contact control."
  []
  (when-enabled :contact nil
    (when @(rf/subscribe [:state [:contact-form :show?]])
      (m/<> {:module :contact
             :view :popup
             :load-on :immediate
             :<before> (fn [] [:span {:role "status"} "Opening contact form…"])}
            true))))

(defc <contact-ways> [email :- :string]
  (let [show-mail-form? @(rf/subscribe [:state [:contact-form :show?]])]
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
        :on-click #(rf/dispatch (if show-mail-form? [:contact/close] [:contact/open]))
        :style {:color "var(--fg-5)"}}
         [:i.fas.fa-envelope]])]]))
