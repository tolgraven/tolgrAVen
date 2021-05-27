(ns tolgraven.chat.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util]))

(defn chat-message "A single chat message"
  [message]
  (let [user @(rf/subscribe [:user/user (:user message)])
        ; now @(rf/subscribe [:now])
        hovered? (r/atom false)]
    (fn [message]
      [:div.chat-message
       [:span.chat-message-time
        {:on-mouse-over #(reset! hovered? true)
         :on-mouse-leave #(reset! hovered? false)}
        (util/timestamp (:time message))
        [:span.chat-message-time-exact
         (when @hovered? (util/unix->ts (:time message)))]]
       
       [:span.chat-message-text (:text message)]
       [:div.chat-message-user.flex
        (or (:name user) "anon")
        (when (:name user) [ui/user-avatar user])]])))

(defn chat "A place to hang out with real-time messaging"
  []
  (let [content @(rf/subscribe [:chat/content])
        latest-id @(rf/subscribe [:chat/latest-seq-id])]
    [:section.covering-2.chat
     [:h3 "Chat"]
     (for [message content]
       [chat-message message])
     [:div.chat-input.flex 
      [ui/input-text
       :path [:form-field [:chat]]
       :placeholder "Message"
       :on-change #(rf/dispatch [:form-field [:chat] %])
       :on-enter #(rf/dispatch [:chat/post latest-id])]
      [:button {:on-click #(rf/dispatch [:chat/post latest-id])}]] ]))

