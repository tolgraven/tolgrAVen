(ns website.modules.gpt.views
  (:require
    [website.component.registry]
    [website.macros :as m :refer-macros [defc]]
    [website.react :as r]

    [clojure.string :as string]
    [website.loader :as l]
    [website.components.ui.inputs :as ui-inputs]
    [website.util :as util]))

(defc <gpt-message> "A single gpt message"
  [prompt response thread]
  (let [user (r/subscribe [:user/user (:user thread)])
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
          @(r/subscribe [:gpt/user-short (:user thread)])
          (m/<> :user/avatar @user)]]
       [:div.gpt-message-text.gpt-message-reply
        (or response
            "...")] ])))

(defc <thread>
  [id]
  (let [open? (r/atom false)
        thread (r/subscribe [:gpt/thread id])]
    (fn [id]
      (let [user @(r/subscribe [:user/user (:user @thread)])]
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
             [ui-inputs/<input-text>
              :path [:form-field [:gpt-thread id]]
              :placeholder "Message"
              :on-enter #(r/dispatch [:gpt/post-in-thread id (:messages @thread)])]
             [:button {:on-click #(r/dispatch [:gpt/post-in-thread id (:messages @thread)])}
              [:i.fa.fa-arrow-right]]]]) ]))))

(defc <threads> "A place to hang out with real-time messaging"
  []
  (let [ids @(r/subscribe [:gpt/thread-ids])]
    [:section.gpt.noborder.covering-2
     [:div.gpt-messages
      {:ref #(when % (set! (.-scrollTop %) (.-scrollHeight %)))}
      (for [id ids] ^{:key (str "gpt-thread-" id)}
        [<thread> id])]
      [:button
       {:on-click #(r/dispatch [:gpt/new-thread])}
       "New thread"]]))
