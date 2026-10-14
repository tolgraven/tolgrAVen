(ns website.modules.blog-editor.editor
  "The post editor supplies its draft identity and privileged library to the shared composer."
  (:require [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.modules.blog-editor.events]
            [website.modules.markdown-editor.authoring :as authoring]))

(def dispose! authoring/dispose!)
(def use-owner authoring/use-owner)
(def upload-actions authoring/upload-actions)
(def enqueue-files! authoring/enqueue-files!)
(def <upload-status> authoring/<upload-status>)

(defc <library-state> []
  (let [{:keys [pending? error next-offset]} @(r/subscribe [:state [:blog-editor :library]])]
    [:div
     (when pending? [:p {:role "status"} "Loading images…"])
     (when error [:p {:role "alert"} error
                  [:button {:type "button", :on-click #(r/dispatch [:blog-editor/library-load 0])} "Retry"]])
     (when next-offset
       [:button {:type "button", :disabled pending?, :on-click #(r/dispatch [:blog-editor/library-load next-offset])} "More images"])]))

(defc <markdown-editor> [spec]
  (let [document-id @(r/subscribe [:state [:blog-editor :draft :document-id]])
        library (r/use-callback
                  (fn [action offset]
                    (case action
                      :load (r/dispatch [:blog-editor/library-load offset])
                      :view [<library-state>])) [])]
    [authoring/<markdown-editor> (assoc spec
                                       :kind :post
                                       :document-id document-id
                                       :library library
                                       :library-query [:state [:blog-editor :library]])]))
