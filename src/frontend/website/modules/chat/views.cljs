(ns website.modules.chat.views
  (:require
    [website.supabase.schema :as schema]
    [website.component.registry]
    [website.macros :as m :refer-macros [defc]]
    [website.react :as r]

    [clojure.string :as string]
    [website.loader :as l]
    [website.modules.link-preview.views :as link-preview]
    [website.components.ui.inputs :as ui-inputs]
    [website.util :as util]))

(defc <chat-message> "A single chat message"
  [message :- schema/chat-message]
  (let [user @(r/subscribe [:user/user (:user message)])
        hovered? (r/atom false)]
    (fn [message]
      [:div.chat-message
        [:span.chat-message-time
         {:on-mouse-over #(reset! hovered? true)
          :on-mouse-leave #(reset! hovered? false)}
         (util/timestamp (:time message))
         [:span.chat-message-time-exact
          (when @hovered? (util/unix->ts (:time message)))]]

        [:div.chat-message-text
         [link-preview/<md>
          (:text message)
          {:id (str "chat-message-" (:id message))
           :trust (if (#{nil "anon"} (:user message)) :untrusted :user)}]]
        [:div.chat-message-user.flex
         (or (:name user) "anon")
         (m/<> :user/avatar user)]])))

(defc <chat> "A place to hang out with real-time messaging"
  []
  (let [content @(r/subscribe [:chat/content])]
    [:section.chat.noborder.covering-2
     [:div.chat-messages
      {:ref #(when % (set! (.-scrollTop %) (.-scrollHeight %)))}
      (for [message content] ^{:key (str "chat-message-" (:id message))}
        [<chat-message> message])]
     [:div.chat-input.flex
      [ui-inputs/<input-text>
       :path [:form-field [:chat]]
       :placeholder "Message"
       :on-enter #(r/dispatch [:chat/post])]
      [:button {:disabled @(r/subscribe [:state [:supabase-writes :chat]])
                :on-click #(r/dispatch [:chat/post])}
       [:i.fa.fa-arrow-right]]]
     [:p.chat-description
      [:b "Step 1. "] "Open two browser windows." [:br]
      [:b "Step 2. "] "Talk to yourself in real time"] ]))
