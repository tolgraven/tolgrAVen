(ns website.modules.contact.views
  (:require [website.component.registry]
            [website.macros :refer-macros [defc]]
            [website.react :as r]

            [clojure.string :as string]
            [website.components.ui.controls :as ui-controls]
    [website.components.ui.inputs :as ui-inputs]
    [website.components.ui.loading :as ui-loading]))

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
  [:div [ui-inputs/<input-text>
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
  {:features [[:appear {:viewport? false}] :exit]}
  []
  (let [[submit-hovered? set-submit-hovered!] (r/use-state false)
        *opener (r/use-ref nil)
        *loading? (r/subscribe [:loading :post]) ; seems bit bruteforcy haha
        *contents (r/subscribe [:form-field [:contact]])
        sent? @(r/subscribe [:state [:contact-form :sent?]])]
    (r/use-layout-effect
     (fn []
       (when (exists? js/document)
         (set! (.-current *opener) (.-activeElement js/document)))
       #(set! (.-current *opener) nil)) [])
    [:section.contact-form-popup
     {:class (when (or sent? @*loading?) "result")}
     [ui-controls/<close>
      (fn [_]
        ;; Move focus before presence applies aria-hidden/inert during exit.
        ;; A lazy panel owns its native opener; no application state is mirrored.
        (when (exists? js/document)
          (let [opener (.-current *opener)
                active (.-activeElement js/document)]
            (when (and opener (.-isConnected opener))
              (.focus opener #js {:preventScroll true}))
            (when (and active (identical? active (.-activeElement js/document)))
              (.blur active))))
        (r/dispatch [:contact/close]))]
     [:h2 "Get in touch"]

     (when sent?
       [:div
        [:br] [:br]
        [<contact-confirmation>]
        [:br]
        [<contact-followup>]])
     [ui-loading/<loading-spinner> *loading? :massive]

     [:form.contact-form-form
      {:style {:height (when (or sent? @*loading?) 0)}}
      [ui-inputs/<input-text>
       :placeholder "Name"
       :width "50%"
       :path [:form-field [:contact :name]]]
      [ui-inputs/<input-text>
       :input-type :input.email
       :type "email" :placeholder "Email"
       :width "50%"
       :path [:form-field [:contact :email]]]
      [ui-inputs/<input-text>
       :placeholder "Title"
       :width "100%"
       :path [:form-field [:contact :title]]]
      [<contact-message>]
      (let [disabled? (or (string/blank? (:email @*contents))
                          (not (string/index-of (:email @*contents) "@"))
                          (string/blank? (:message @*contents)))]
        [:div.flex
         {:on-mouse-enter #(set-submit-hovered! true)
          :on-mouse-leave #(set-submit-hovered! false)}
         [:input
          {:type "submit" :id "submit-contact"
           :disabled disabled?
           :title (when-not disabled? "Ready to go!")
           :on-click (fn [e]
                       (.preventDefault e)
                       (r/dispatch [:contact/send-request]))}]
         (if (and disabled? submit-hovered?) ; mouseLeave never fires (wtf??) but still good enough I suppose
           [<contact-submit-hint>]
           [:br])
         [:p "Whether for work, collaboration or something else, I'll do my best to accomodate you.
              NOTE! Currently out of order, please just email me for now haha."]])]]))

(defc <contact-form-host>
  {:features [:presence]}
  []
  [:<>
   (when @(r/subscribe [:state [:contact-form :show?]])
     ^{:key :contact-form} [<contact-form-popup>])])
