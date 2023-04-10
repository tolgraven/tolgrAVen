(ns tolgraven.gpt.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util]))

(defn gpt-message "A single gpt message"
  [message]
  (let [user @(rf/subscribe [:user/user (:user message)])
        hovered? (r/atom false)]
    (fn [message]
      [:div.gpt-message
       
       
       [:div.gpt-message-text.gpt-message-prompt
        [:span (:prompt message)]
        [:span.gpt-message-time
         {:on-mouse-over #(reset! hovered? true)
          :on-mouse-leave #(reset! hovered? false)}
         (util/timestamp (:time message))
         [:span.gpt-message-time-exact
          (when @hovered? (util/unix->ts (:time message)))]]
        [:div.gpt-message-user.flex
         (or (:name user) "anon")
         [ui/user-avatar user]]]
       [:div.gpt-message-text.gpt-message-reply (or (:response message)
                                                    "...")]
       ])))

(defn gpt "A place to hang out with real-time messaging"
  []
  (let [content @(rf/subscribe [:gpt/content])
        latest-id @(rf/subscribe [:gpt/latest-seq-id])]
    [:section.gpt.noborder.covering-2
     [:div.gpt-messages
      {:ref #(when % (set! (.-scrollTop %) (.-scrollHeight %)))}
      (for [message content] ^{:key (str "gpt-message-" (:time message) "-" (:user message))}
        [gpt-message message])]
     [:div.gpt-input.flex 
      [ui/input-text
       :path [:form-field [:gpt]]
       :placeholder "Message"
       :on-enter #(rf/dispatch [:gpt/post latest-id])]
      [:button {:on-click #(rf/dispatch [:gpt/post latest-id])}
       [:i.fa.fa-arrow-right]]] ]))

