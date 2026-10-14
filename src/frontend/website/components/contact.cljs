(ns website.components.contact
  (:require-macros [website.build.site :refer [when-enabled]])
  (:require [website.component.registry]
            [website.macros :refer-macros [defc]]
            [website.react :as r]))

(defc <contact-ways> [email :- :string]
  (let [[mail-ready? set-mail-ready!] (r/use-state false)
        show-mail-form? @(r/subscribe [:state [:contact-form :show?]])]
    ;; Mail-handler extensions rewrite mailto anchors before hydration. Keep
    ;; SSR and the first client render identical; React adds the link after commit.
    (r/use-effect
     (fn []
       (let [frame (js/requestAnimationFrame (fn [] (set-mail-ready! true)))]
         #(js/cancelAnimationFrame frame))) [])
    [:div
     [:div.contact-ways
      [:span [:a {:href (when mail-ready? (str "mailto:" email))
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
