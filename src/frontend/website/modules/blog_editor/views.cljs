(ns website.modules.blog-editor.views
  (:require [website.react :as r]
            [website.macros :refer-macros [defc defpage]]
            [website.components.ui.controls :as controls]
            [website.components.ui.inputs :as inputs]
            [website.components.ui.layout :as layout]
            [website.modules.blog.presentation :as blog]
            [website.modules.blog-editor.editor :as editor]
            [website.modules.blog-editor.draft :as draft]
            [website.modules.markdown-editor.model :as editor-model]))

(defc <preview-blog> "Render new post preview"
  [{{:keys [title text image-assets]} :post :keys [owner] :as spec}]
  (let [user @(r/subscribe [:user/active-user])
        user-id (if (string? user) user (:id user))
        uploads (filter #(and (= owner (:owner %)) (= :ready (:status %)))
                        (vals @(r/subscribe [:blog/state [:image-uploads]])))
        generations (editor-model/preview-generations uploads)
        ;; Status acknowledgments must not replace the adapted image component:
        ;; only its source generation or owner changes these callback identities.
        readiness (r/use-memo
                    (fn []
                      (let [acknowledge! (fn [event src]
                                           (doseq [[id attempt generation] (get generations src)]
                                             (r/dispatch [event id attempt src owner generation])))]
                        {:ready! #(acknowledge! :blog/image-preview-ready %)
                         :failed! #(acknowledge! :blog/image-preview-failed %)
                         :generations generations}))
                    [owner generations])
        trust (cond (nil? user-id) :untrusted
                    @(r/subscribe [:user/trusted? user-id]) :trusted
                    :else :user)]
   [:div
    [:h2.blog-post-title title]
    [:br]
    [blog/<markdown> text
     {:trust trust
      :image-assets image-assets
      :image-readiness readiness}]]))

(defc <draft-controls>
  "Restore before tracking the working draft; the shared storage lifecycle owns autosave."
  [owner]
  (let [account @(r/subscribe [:state [:active-user :id]])
        editing-id (:id @(r/subscribe [:blog/state [:editing]]))
        state @(r/subscribe [:state [:blog-editor :draft]])
        uploads @(r/subscribe [:blog-editor/uploads owner])
        projection @(r/subscribe [:blog-editor/draft-projection owner])
        entries (when (= account (:account state)) (get-in projection [:collection :entries]))
        id [:blog-editor/working (or editing-id :new)]]
    (r/use-effect
      (fn []
        (r/dispatch [:blog/load-draft owner])
        #(r/dispatch [:blog/draft-unmount owner id])) [owner account editing-id])
    [:section.blog-draft-controls
     [controls/<button> "Save draft" :save-blog-draft
      :disabled? (boolean (or (nil? account) (nil? (:document-id state)) (#{:loading :saving} (:status state))))
      :action #(r/dispatch [:blog/save-draft owner])]
     (when (seq entries)
       [:label "In-progress posts"
        [:select {:value (if (:named? state) (:document-id state) "")
                  :on-change #(r/dispatch [:blog/select-draft owner (.. % -target -value)])}
         [:option {:value "", :disabled true} "Choose a draft…"]
         (for [[id entry] (sort-by #(get-in (val %) [:form :title]) entries)]
           ^{:key id} [:option {:value id} (get-in entry [:form :title])])]])
     [:button {:type "button", :disabled (= :loading (:status state))
               :on-click #(r/dispatch [:blog/new-draft owner])} (if editing-id "New editing draft" "New draft")]
     (when (= owner (:owner state))
       [:p.blog-draft-status {:role (if (:error state) "alert" "status")}
        (case (:status state)
          :loading "Checking for autosaved text…"
          :saving "Saving draft…"
          :saved "Named draft saved. Changes continue to autosave in this browser. "
          :restored "Autosaved draft restored. "
          :failed nil
          "Changes autosave in this browser. ")
        (:error state)
        (when (editor-model/unresolved? nil (into {} (map (juxt :id identity)) uploads))
          " Pending images stay in this tab until their upload finishes; they are not saved in drafts.")
        (when (= :failed (:status state))
          [:button {:type "button", :on-click #(r/dispatch [(if (= :save (:operation state)) :blog/save-draft :blog/load-draft) owner])}
           "Retry draft storage"])])]))

(defc <post-blog> "Render post-making ui"
  {:features [:error-boundary]}
  [] ; XXX move this and similar to own file...
  (let [input @(r/subscribe [:form-field [:post-blog]])
        user @(r/subscribe [:user/active-user])
        editing @(r/subscribe [:blog/state [:editing]])
        uploads @(r/subscribe [:blog/state [:image-uploads]])
        account @(r/subscribe [:state [:active-user :id]])
        ;; Account changes end the old queue/transport owner even while this
        ;; route remains mounted. Draft document identity is restored separately.
        owner (editor/use-owner account)]
    [:section.blog.blog-new-post
     [:h2 "Write blog post"]
     [:br]

     [inputs/<input-text>
      :placeholder "Title"
      :path [:form-field [:post-blog :title]]]

     [inputs/<input-text>
      :placeholder "Tags"
      :path [:form-field [:post-blog :tags]]]

     ^{:key owner} [editor/<markdown-editor> {:owner owner, :exit (partial editor/dispose! owner)}]

     [<draft-controls> owner]
     [controls/<button> "Highlight code" :highlight-blog-code
      :action #(r/dispatch [:run-highlighter!])]

     [:br]
     [:section.blog-post-preview
      [<preview-blog> {:owner owner, :post (assoc input :image-assets
                                  (merge (:image-assets input)
                                         (into {} (keep (fn [{:keys [asset]}]
                                                         (when asset [(:src asset) asset])))
                                               (vals uploads))))}]]

     [:section
      [controls/<button> "Submit" :post-new-blog
       :disabled? (editor-model/unresolved? (:text input) uploads)
       :action #(do (r/dispatch [:blog/submit
                                  (merge {:user user} input)
                                  editing])
                    nil)]
      [:button {:on-click #(r/dispatch [:common/navigate! :blog])} ; triggers controller hence cleanup
       [:label "Cancel"]]]]))

(defpage <page> []
  [layout/<with-heading> [:blog :heading] [<post-blog>]])
