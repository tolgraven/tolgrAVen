(ns website.modules.content-editor.views
  (:require [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.modules.content-editor.events]))

(defc <controls>
  []
  (let [user @(r/subscribe [:user/active-user])
        admin? @(r/subscribe [:user/has-role? :admins])
        {:keys [settings enabled? fields error]} @(r/subscribe [:get :state :content-editor])
        busy? (some #(#{:saving :uploading} (:status %)) (vals fields))]
    (r/use-effect #(when admin? (r/dispatch [:content-editor/open])) [admin? (:id user)])
    (when admin?
      [:div.content-editor-controls
       (when-let [url (:admin-url settings)]
         [:a.button {:href url :target "_blank" :rel "noopener noreferrer"} "Open Strapi admin"])
       (if (:writable? settings)
         [:button {:on-click #(r/dispatch [:content-editor/toggle])
                   :aria-pressed (boolean enabled?)} (if enabled? "Finish editing" "Edit site content")]
         [:p "Inline editing is not configured."])
       (when error [:p {:role "alert"} error])
       (when error [:button {:on-click #(r/dispatch [:content-editor/open])} "Retry"])])))

(defc <toolbar> []
  (let [mode @(r/subscribe [:content/mode])
        {:keys [fields enabled?]} @(r/subscribe [:get :state :content-editor])
        user @(r/subscribe [:user/active-user])
        editor-owner @(r/subscribe [:get :state :content-editor :owner])
        admin? @(r/subscribe [:user/has-role? :admins])
        busy? (some #(#{:saving :uploading} (:status %)) (vals fields))
        changed (filter #(not= (:expected %) (:value %)) (vals fields))]
    (when (and admin? (= (:id user) editor-owner) (or (= :edit mode) (seq fields)))
      [:div.content-editor-toolbar {:role "region" :aria-label "Site content editor"}
          [:span (if busy? "Saving / processing image…" (str (count changed) " changed fields"))]
          [:button {:disabled (or busy? (empty? changed))
                    :on-click #(r/dispatch [:content-editor/save-all])} "Save changes"]
          [:button {:disabled (or busy? (empty? fields))
                    :on-click #(r/dispatch [:content-editor/discard])} "Discard changes"]
          (when (seq changed) [:span "Changes become public when saved."])
          (for [[key draft] fields :when (:error draft)]
            ^{:key key} [:p {:role "alert"} (:error draft)])
          [:button {:on-click #(r/dispatch [:content-editor/toggle])}
           (if enabled? "Finish editing" "Resume editing")]])))
