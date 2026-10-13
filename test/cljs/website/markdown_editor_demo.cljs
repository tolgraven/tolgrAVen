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
