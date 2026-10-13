(ns tolgraven.modules.blog-editor.views
  (:require [tolgraven.react :as r]
            [tolgraven.macros :refer-macros [defc defpage]]
            [tolgraven.components.ui :as ui]
            [tolgraven.modules.blog.views :as blog]
            [tolgraven.modules.blog-editor.editor :as editor]
            [tolgraven.modules.blog-editor.model :as editor-model]))

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

     [ui/<input-text>
      :placeholder "Title"
      :path [:form-field [:post-blog :title]]]

     [ui/<input-text>
      :placeholder "Tags"
      :path [:form-field [:post-blog :tags]]]

     [editor/<markdown-editor> {:owner owner, :exit (partial editor/dispose! owner)}]

     [ui/<button> "Save draft" :save-blog-draft]
     [ui/<button> "Highlight code" :highlight-blog-code
      :action #(r/dispatch [:run-highlighter!])]

     [:br]
     [:section.blog-post-preview
      [<preview-blog> {:owner owner, :post (assoc input :image-assets
                                  (merge (:image-assets input)
                                         (into {} (keep (fn [{:keys [asset]}]
                                                         (when asset [(:src asset) asset])))
                                               (vals uploads))))}]]

     [:section
      [ui/<button> "Submit" :post-new-blog
       :disabled? (editor-model/unresolved? (:text input) uploads)
       :action #(do (r/dispatch [:blog/submit
                                  (merge {:user user} input)
                                  editing])
                    nil)]
      [:button {:on-click #(r/dispatch [:common/navigate! :blog])} ; triggers controller hence cleanup
       [:label "Cancel"]]]]))

(defpage <page> []
  [ui/<with-heading> [:blog :heading] [<post-blog>]])
