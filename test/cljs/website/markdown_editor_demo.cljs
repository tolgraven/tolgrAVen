(ns website.markdown-editor-demo
  "Persistent browser fixture for native editor, published preview and layout checks."
  (:require [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.db :as db]
            [website.events]
            [website.subs]
            [website.modules.markdown-editor.views :as editor]
            [website.components.content.upload :as upload]
            [website.modules.markdown.views :as markdown]
            [website.component.storage :as storage]
            [website.listener :as listener]
            [website.modules.blog-editor.editor :as blog-editor]
            [website.modules.blog-editor.views :as blog-views]
            [website.modules.blog.presentation :as presentation]
            [website.test-support :as support]))

(def initial-source
  "## Writing with the site's components\n\n**Bold**, *italic*, and a normal link to [the blog](/blog).\n\n- [ ] Review the draft\n- [x] Add a useful example\n\n<Component component=\"website.components.content.box/<highlight-box>\" title=\"A note\" text=\"This is the real reusable site component.\" tone=\"tip\" layout-width=\"40%\" layout-flow=\"right\" />\n\nSurrounding prose flows around the component. Its argument panel edits the same Markdown that is rendered below, and the corner handle commits its final dimensions. More text makes the wrapping geometry visible in both modes.\n\n<Component component=\"website.components.content.markdown/<image>\" src=\"/img/tolgrav-square.png\" alt=\"Site logo\" layout-width=\"12rem\" layout-flow=\"left\" />\n\nAn image uses the same size, alignment and flow controls. It remains a responsive picture in the published preview.\n\n<Component component=\"website.modules.settings.views/<config-table>\" title=\"Reader appearance\" />\n\n<Component component=\"website.components.content.frame/<frame>\" src=\"/fixtures/editor-frame.html\" title=\"Local auto-height frame\" layout-width=\"80%\" layout-height=\"auto\" />\n\n<Component component=\"website.components.content.frame/<frame>\" src=\"/fixtures/editor-frame-cooperating.html\" title=\"Cooperating auto-height frame\" interactive=\"json:true\" layout-width=\"80%\" layout-height=\"auto\" />\n")

(r/reg-event-db :test-editor/initialize (fn [_ _] db/data))

(defc <fixture> []
  :let [*source (r/atom initial-source)]
  [:main.editor-demo
   [:h1 "Blog authoring"]
   [editor/<editor> {:text @*source, :on-change #(reset! *source %)}]
   [:details [:summary "Saved Markdown"] [:pre#saved-markdown @*source]]
   [:h2 "Published preview"]
   [:article#published-preview.md-rendered
    [markdown/<parse-markdown-components> @*source {:allow-images? true}]]])

(defn ^:export init! []
  (r/dispatch-sync [:test-editor/initialize])
  (-> (support/create-root! (.getElementById js/document "editor-demo"))
      (.then #(support/render! % [<fixture>]))))

(defc <comment-fixture> []
  (let [owner (blog-editor/use-owner :comment-demo)
        text @(r/subscribe [:form-field [:write-comment [42] :text]])]
    [:main.editor-demo
     [:h1 "Comment authoring"]
     [blog-editor/<markdown-editor> {:owner owner
                                     :kind :comment
                                     :form-path [:write-comment [42] :text]
                                     :library? false}]
     [:h2 "Published comment preview"]
     [presentation/<comment-markdown> (or text "") {:trust :trusted}]]))

(defn ^:export init-comment! []
  (r/dispatch-sync [:test-editor/initialize])
  (r/dispatch-sync [:form-field [:write-comment [42] :text]
                    "## A comment\n\n**Bold**, *italic*, lists, links and code use the full editor.\n\n- First point\n- Second point\n\n```clojure\n(map inc [1 2 3])\n```"])
  (-> (support/create-root! (.getElementById js/document "editor-demo"))
      (.then #(support/render! % [<comment-fixture>]))))

(defc <upload-fixture> []
  [:main.editor-demo
   [:h1 "Image upload states"]
   [:ul.blog-image-editor__uploads
    [upload/<upload-row> {:name "gallery-photo.png", :status :uploading, :progress {:loaded 40, :total 100}}]
    [upload/<upload-row> {:name "portrait.heic", :status :processing}]
    [upload/<upload-row> {:name "banner.jpg", :status :ready}]
    [upload/<upload-row> {:name "cover.png", :status :failed, :error "Upload interrupted. Retry or remove the image.", :retry! (fn []), :remove! (fn [])}]]])

(defn ^:export init-uploads! []
  (-> (support/create-root! (.getElementById js/document "editor-demo"))
      (.then #(support/render! % [<upload-fixture>]))))

(defc <draft-fixture> []
  (let [account @(r/subscribe [:state [:active-user :id]])
        owner (blog-editor/use-owner account)
        form @(r/subscribe [:form-field [:post-blog]])
        state @(r/subscribe [:state [:blog-editor :draft]])]
    (r/use-effect
      (fn []
        (listener/listen! :draft-fixture
          [{:target "window", :event "pagehide"
            :handler (fn [_] (storage/flush! :navigation) (storage/drain!))}])) [])
    [:main.editor-demo
     [:h1 "Draft storage fixture"]
     [:label "Title" [:input {:value (or (:title form) "")
                              :on-change #(r/dispatch [:form-field [:post-blog :title] (.. % -target -value)])}]]
     [:label "Tags" [:input {:value (or (:tags form) "")
                             :on-change #(r/dispatch [:form-field [:post-blog :tags] (.. % -target -value)])}]]
     ^{:key owner} [blog-editor/<markdown-editor> {:owner owner, :source? true}]
     [blog-views/<draft-controls> owner]
     [:p {:id "draft-document-id"} (:document-id state)]
     [:details [:summary "Canonical Markdown"] [:pre#saved-markdown (:text form)]]
     [:h2 "Published preview"]
     [markdown/<parse-markdown-components> (or (:text form) "") {:allow-images? true}]]))

(defn ^:export init-drafts! []
  ;; A fixed disposable account tests actual document reloads without using
  ;; credentials, private user records, or a live upload transport.
  (r/dispatch-sync [:test-editor/initialize])
  (r/dispatch-sync [:state [:active-user] {:id 990}])
  (storage/identity! ["local-draft-fixture" 990])
  (-> (support/create-root! (.getElementById js/document "editor-demo"))
      (.then #(support/render! % [<draft-fixture>]))))
