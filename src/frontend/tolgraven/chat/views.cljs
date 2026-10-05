(ns tolgraven.chat.views
  (:require
    [tolgraven.supabase.schema :as schema]
    [tolgraven.component.registry]
    [tolgraven.macros :as m :refer-macros [defc]]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [clojure.string :as string]
    [tolgraven.loader :as l]
    [tolgraven.link-preview.views :as link-preview]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]))

(defc <chat-message> "A single chat message"
  [message :- schema/chat-message]
  (let [user @(rf/subscribe [:user/user (:user message)])
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
  (let [content @(rf/subscribe [:chat/content])]
    [:section.chat.noborder.covering-2
     [:div.chat-messages
      {:ref #(when % (set! (.-scrollTop %) (.-scrollHeight %)))}
      (for [message content] ^{:key (str "chat-message-" (:id message))}
        [<chat-message> message])]
     [:div.chat-input.flex
      [ui/<input-text>
       :path [:form-field [:chat]]
       :placeholder "Message"
       :on-enter #(rf/dispatch [:chat/post])]
      [:button {:disabled @(rf/subscribe [:state [:supabase-writes :chat]])
                :on-click #(rf/dispatch [:chat/post])}
       [:i.fa.fa-arrow-right]]]
     [:p.chat-description
      [:b "Step 1. "] "Open two browser windows." [:br]
      [:b "Step 2. "] "Talk to yourself in real time"] ]))
