(ns tolgraven.modules.gpt.views
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :as m :refer-macros [defc]]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [clojure.string :as string]
    [tolgraven.loader :as l]
    [tolgraven.components.ui :as ui]
    [tolgraven.util :as util]))

(defc <gpt-message> "A single gpt message"
  [prompt response thread]
  (let [user (rf/subscribe [:user/user (:user thread)])
        hovered? (r/atom false)]
    (fn [prompt response thread]
      [:div.gpt-message
       [:div.gpt-prompt-container.flex
        [:div.gpt-message-text.gpt-message-prompt
         [:span prompt]
         [:span.gpt-message-time
          {:on-mouse-over #(reset! hovered? true)
           :on-mouse-leave #(reset! hovered? false)}
          (util/timestamp (:time thread))
          [:span.gpt-message-time-exact
           (when @hovered? (util/unix->ts (:time thread)))]]]
         [:div.gpt-message-user.flex
          @(rf/subscribe [:gpt/user-short (:user thread)])
          (m/<> :user/avatar @user)]]
       [:div.gpt-message-text.gpt-message-reply
        (or response
            "...")] ])))

(defc <thread>
  [id]
  (let [open? (r/atom false)
        thread (rf/subscribe [:gpt/thread id])]
    (fn [id]
      (let [user @(rf/subscribe [:user/user (:user @thread)])]
        [:div.gpt-thread-container
         [:div.flex
          {:style {:align-items "center"}}
          [:span "Thread "]
          [:button.gpt-open-thread
           {:on-click #(swap! open? not)}
           id]

          [:div.gpt-message-user.flex
           (or (:name user) "anon")
           (m/<> :user/avatar user)]]

         (when @open?
           [:div.gpt-messages.gpt-thread.open
            {:ref #(when % (set! (.-scrollTop %) (.-scrollHeight %)))}

            (for [[prompt response] (partition 2 2 "..." (:messages @thread))]
              ^{:key (str "gpt-message-" (:user @thread) "-" (:time @thread))}
              [<gpt-message> prompt response @thread])

            [:div.gpt-input.flex
             [ui/<input-text>
              :path [:form-field [:gpt-thread id]]
              :placeholder "Message"
              :on-enter #(rf/dispatch [:gpt/post-in-thread id (:messages @thread)])]
             [:button {:on-click #(rf/dispatch [:gpt/post-in-thread id (:messages @thread)])}
              [:i.fa.fa-arrow-right]]]]) ]))))

(defc <threads> "A place to hang out with real-time messaging"
  []
  (let [ids @(rf/subscribe [:gpt/thread-ids])]
    [:section.gpt.noborder.covering-2
     [:div.gpt-messages
      {:ref #(when % (set! (.-scrollTop %) (.-scrollHeight %)))}
      (for [id ids] ^{:key (str "gpt-thread-" id)}
        [<thread> id])]
      [:button
       {:on-click #(rf/dispatch [:gpt/new-thread])}
       "New thread"]]))
