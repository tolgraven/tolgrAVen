(ns tolgraven.markdown-editor-demo
  "Persistent browser fixture for native editor, published preview and layout checks."
  (:require [tolgraven.react :as r]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.db :as db]
            [tolgraven.events]
            [tolgraven.subs]
            [tolgraven.modules.markdown-editor.views :as editor]
            [tolgraven.components.ui.code :as markdown]
            [tolgraven.test-support :as support]))

(def initial-source
  "## Writing with the site's components\n\n**Bold**, *italic*, and a normal link to [the blog](/blog).\n\n- [ ] Review the draft\n- [x] Add a useful example\n\n<Component component=\"tolgraven.components.content.box/<highlight-box>\" title=\"A note\" text=\"This is the real reusable site component.\" tone=\"tip\" layout-width=\"40%\" layout-flow=\"right\" />\n\nSurrounding prose flows around the component. Its argument panel edits the same Markdown that is rendered below, and the corner handle commits its final dimensions. More text makes the wrapping geometry visible in both modes.\n\n<Component component=\"tolgraven.components.content.markdown/<image>\" src=\"/img/tolgrav-square.png\" alt=\"Site logo\" layout-width=\"12rem\" layout-flow=\"left\" />\n\nAn image uses the same size, alignment and flow controls. It remains a responsive picture in the published preview.\n\n<Component component=\"tolgraven.modules.settings.views/<config-table>\" title=\"Reader appearance\" />\n\n<Component component=\"tolgraven.components.content.frame/<frame>\" src=\"/fixtures/editor-frame.html\" title=\"Local auto-height frame\" layout-width=\"80%\" layout-height=\"auto\" />\n\n<Component component=\"tolgraven.components.content.frame/<frame>\" src=\"/fixtures/editor-frame-cooperating.html\" title=\"Cooperating auto-height frame\" interactive=\"json:true\" layout-width=\"80%\" layout-height=\"auto\" />\n")

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
