(ns website.modules.content-editor.views
  (:require [website.react :as r]
            [website.content.fields :as content-fields]
            [website.macros :refer-macros [defc]]
            [website.modules.content-editor.events]
            [website.modules.content-editor.schema :as schema])
  (:require-macros [website.build.site :refer [when-enabled]]))

(defc <controls>
  []
  (let [user @(r/subscribe [:user/active-user])
        admin? @(r/subscribe [:user/has-role? :admins])
        {:keys [settings enabled? error]} @(r/subscribe [:get :state :content-editor])]
    (r/use-effect #(when admin? (r/dispatch [:content-editor/open])) [admin? (:id user)])
    (when admin?
      [:div.content-editor-controls
       (when-let [url (:admin-url settings)]
         [:a.button {:href url :target "_blank" :rel "noopener noreferrer"} "Open Strapi admin"])
       (cond
         (and (nil? settings) (nil? error)) [:p {:role "status"} "Loading content tools…"]
         (:writable? settings)
         [:button {:on-click #(r/dispatch [:content-editor/toggle])
                   :aria-pressed (boolean enabled?)} (if enabled? "Finish editing" "Edit site content")]
         :else [:p "Inline editing is not configured."])
       (when error [:p {:role "alert"} error])
       (when error [:button {:on-click #(r/dispatch [:content-editor/open])} "Retry"])])))

(when-enabled :cv nil
  (defc <cv-entry-form>
    [{:keys [category parents fields status error conflict?]} :- schema/cv-entry-draft other-changes? :- :boolean]
    (let [saving? (= :saving status)
          input (fn [key label attrs]
                  [:label label
                   [:input (merge {:value (get fields key)
                                   :disabled saving?
                                   :on-change #(r/dispatch-sync [:content-editor/cv-change key (.. % -target -value)])}
                                  attrs)]])]
      [:section.content-editor-entry-panel {:role "dialog", :aria-label (str "Add CV entry to " category)}
       [:form {:on-submit (fn [event]
                            (.preventDefault event)
                            (r/dispatch [:content-editor/cv-create (.getUTCFullYear (js/Date.))]))}
        [:h2 "Add entry to " category]
        [:p "The entry becomes public when created."]
        [:label "Entry type"
         [:select {:value (:kind fields)
                   :disabled saving?
                   :on-change #(r/dispatch-sync [:content-editor/cv-change :kind (.. % -target -value)])}
          [:option {:value "regular"} "Regular box"]
          (when (< (count parents) 6) [:option {:value "container"} "Timeline container"])]]
        (input :what (if (= "container" (:kind fields)) "Timeline title" "Role / qualification")
               {:required true, :max-length 200, :auto-focus true})
        (when (= "container" (:kind fields)) [:p "Opens a mini timeline, starting with one editable New entry box."])
        (when (= "container" (:kind fields))
          [:label "Description"
           [:textarea {:value (:position fields)
                       :disabled saving?
                       :max-length 2000
                       :on-change #(r/dispatch-sync [:content-editor/cv-change :position (.. % -target -value)])}]])
        (when (= "regular" (:kind fields)) (input :where "Organisation" {:max-length 200}))
        [:div.content-editor-entry-years
         (input :from "Start year" {:type "number", :required true, :min 1600, :max 3000})
         (input :to "End year (blank for current)" {:type "number", :min 1600, :max 3000})]
        (when (= "regular" (:kind fields))
         [:label "Details (one per line)"
         [:textarea {:value (:how fields)
                     :disabled saving?
                     :max-length 64000
                     :on-change #(r/dispatch-sync [:content-editor/cv-change :how (.. % -target -value)])}]])
        (when other-changes? [:p "Save or discard your other changes before creating this entry."])
        (when error [:p {:role "alert"} error])
        [:div.content-editor-entry-actions
         (if conflict?
           [:button {:type "button", :disabled saving?, :on-click #(r/dispatch [:content-editor/cv-cancel])}
            "Review current entries"]
           [:button {:type "submit", :disabled (or saving? other-changes?)}
            (if saving? "Creating…" "Create entry")])
         [:button {:type "button", :disabled saving?, :on-click #(r/dispatch [:content-editor/cv-cancel])} "Cancel"]]]])))


(defc <toolbar> []
  (let [mode @(r/subscribe [:content/mode])
        {:keys [fields enabled? cv-entry] :as editor} @(r/subscribe [:get :state :content-editor])
        user @(r/subscribe [:user/active-user])
        editor-owner @(r/subscribe [:get :state :content-editor :owner])
        admin? @(r/subscribe [:user/has-role? :admins])
        busy? (content-fields/busy? editor)
        changed (filter #(not= (:expected %) (:value %)) (vals fields))]
    (when (and admin? (= (:id user) editor-owner) (or (= :edit mode) (seq fields) cv-entry))
      [:div.content-editor-toolbar {:role "region" :aria-label "Site content editor"}
       [:span (if busy? "Saving / processing image…" (str (count changed) " changed fields"))]
       [:button {:disabled (or busy? (empty? changed))
                 :on-click #(r/dispatch [:content-editor/save-all])} "Save changes"]
       [:button {:disabled (or busy? (and (empty? fields) (nil? cv-entry)))
                 :on-click #(r/dispatch [:content-editor/discard])} "Discard changes"]
       (when (seq changed) [:span "Changes become public when saved."])
       (for [[key draft] fields :when (:error draft)]
         ^{:key key}
         [:div
          [:p {:role "alert"} (:error draft)]
          (when (:conflict? draft)
            [:button {:disabled busy?
                      :on-click #(r/dispatch [:content-editor/discard-field key])}
             "Use latest value"])])
       [:button {:on-click #(r/dispatch [:content-editor/toggle])}
        (if enabled? "Finish editing" "Resume editing")]
       (when-enabled :cv nil
         (when cv-entry [<cv-entry-form> cv-entry (boolean (seq fields))]))])))
