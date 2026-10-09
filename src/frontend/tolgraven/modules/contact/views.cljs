(ns tolgraven.modules.contact.views
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [reagent.core :as r]
            [clojure.string :as string]
            [tolgraven.components.ui :as ui]))

(defc ^:private <contact-confirmation>
  {:features [[:appear "slide-in"]]}
  []
  [:h2 "Your message has been sent!"])

(defc ^:private <contact-followup>
  {:features [[:appear "opacity"]]}
  []
  [:h3 "I'll get back to you shortly."])

(defc ^:private <contact-message>
  {:features [[:appear "slide-in slow"]]}
  []
  [:div [ui/<input-text>
            :placeholder "Message"
            :input-type :textarea
            :width "100%"
            :height "15em"
            :min-rows 8
            :path [:form-field [:contact :message]]]])

(defc ^:private <contact-submit-hint>
  {:features [[:appear "slide-in slower"]]}
  []
  [:label {:for "submit-contact"}
                 "Must enter at least email and message"])

(defc <contact-form-popup>
  [_]
  (let [*inited? (r/atom nil)
        *submit-hovered? (r/atom false)]
    (fn [show?]
      (when show?
        (let [*loading? (rf/subscribe [:loading :post]) ; seems bit bruteforcy haha
              *contents (rf/subscribe [:form-field [:contact]])
              *contact-form-state (rf/subscribe [:state [:contact-form]])
              {:keys [show? sent? closing?]} @*contact-form-state]
        [:section.contact-form-popup
         {:class (str
                  (when closing? "closing ")
                  (when (or sent? @*loading?) "result ")
                  (when @*inited? "inited"))
          :ref #(when % (reset! *inited? true))}
         [ui/<close> #(rf/dispatch [:contact/close])]
         [:h2 "Get in touch"]

         (when sent?
           [:div
            [:br] [:br]
            [<contact-confirmation>]
            [:br]
            [<contact-followup>]])
         [ui/<loading-spinner> *loading? :massive]

         [:form.contact-form-form
          {:style {:height (when (or sent? @*loading?) 0)}}
          [ui/<input-text>
           :placeholder "Name"
           :width "50%"
           :path [:form-field [:contact :name]]]
          [ui/<input-text>
           :input-type :input.email
           :type "email" :placeholder "Email"
           :width "50%"
           :path [:form-field [:contact :email]]]
          [ui/<input-text>
           :placeholder "Title"
           :width "100%"
           :path [:form-field [:contact :title]]]
          [<contact-message>]
          (let [disabled? (or (string/blank? (:email @*contents))
                              (not (string/index-of (:email @*contents) "@"))
                              (string/blank? (:message @*contents)))]
            [:div.flex
             {:on-mouse-enter #(reset! *submit-hovered? true)
              :on-mouse-leave #(reset! *submit-hovered? false)}
             [:input
              {:type "submit" :id "submit-contact"

               :disabled disabled?
               :title (when-not disabled? "Ready to go!")
               :on-click (fn [e]
                           (.preventDefault e)
                           (rf/dispatch [:contact/send-request]))}]
             (if (and disabled? @*submit-hovered?) ; mouseLeave never fires (wtf??) but still good enough I suppose
               [<contact-submit-hint>]
               [:br])
             [:p "Whether for work, collaboration or something else, I'll do my best to accomodate you.
                  NOTE! Currently out of order, please just email me for now haha."]])]])))))

(defc <contact-ways> [email]
  (let [show-mail-form? @(rf/subscribe [:state [:contact-form :show?]])]
    [:div
     [<contact-form-popup> show-mail-form?]
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
