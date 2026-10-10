(ns tolgraven.components.contact
  (:require [tolgraven.component.registry]
            [tolgraven.macros :as m :refer-macros [defc]]
            [tolgraven.react :as rf]
            [tolgraven.loader]))

(defc <contact-ways> [email :- :string]
  (let [show-mail-form? @(rf/subscribe [:state [:contact-form :show?]])]
    [:div
     (when show-mail-form?
       (m/<> {:module :contact
              :view :popup
              :load-on :immediate
              :<before> (fn [] [:span {:role "status"} "Opening contact form…"])}
             true))
     [:div.contact-ways
      [:span [:a {:href (str "mailto:" email)
                  :style {:font-size "85%"}}
              email]]
      [:span {:style {:color "var(--fg-6)"}}
       " | "]
      [:button.nomargin.nopadding.noborder
       {:title "Contact us by form"
        :on-click #(rf/dispatch (if show-mail-form? [:contact/close] [:contact/open]))
        :style {:color "var(--fg-5)"}}
       [:i.fas.fa-envelope]]]]))
