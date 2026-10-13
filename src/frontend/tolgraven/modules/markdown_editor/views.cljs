(ns tolgraven.modules.markdown-editor.views
  "The SDK owns selection, undo and its editable DOM; application content stays Markdown."
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as r]
            [tolgraven.modules.markdown-editor.catalog :as catalog]
            [tolgraven.modules.markdown-components.model :as model]
            [tolgraven.modules.markdown-components.views :as components]
            [tolgraven.components.content.layout :as layout]
            [tolgraven.components.content.resize :as resize]
            [tolgraven.components.content.media :as media]
            [clojure.string :as string]
            ["@mdxeditor/editor" :as editor]
            ["lexical" :refer [FORMAT_TEXT_COMMAND $getNodeByKey $getSelection $setSelection $isRangeSelection]]))

(def mdx-editor (r/adapt-react-class editor/MDXEditor))

(defc <component-picker>
  "Search exposed names, documentation and argument schemas before inserting a component."
  []
  :let [*query (r/atom "")
        *open? (r/atom false)]
  (let [insert! (editor/usePublisher editor/insertJsx$)]
    [:div.md-component-picker
     [:button {:type "button"
               :aria-expanded @*open?
               :on-click #(swap! *open? not)} "Component"]
     (when @*open?
       [:div.md-component-picker__menu
        [:label "Find component"
         [:input {:type "search"
                  :value @*query
                  :on-change #(reset! *query (.. % -target -value))}]]
        [:div.md-component-picker__results
         (for [{:keys [name doc category] :as entry} (catalog/search @*query)]
           ^{:key name}
           [:button {:type "button"
                     :on-click #(do (insert! (clj->js {:kind "flow"
                                                       :name "Component"
                                                       :props (assoc (into {} (map (fn [[key value]]
                                                                              [(cljs.core/name key) (model/encode-value value)]))
                                                                           (model/defaults entry)) "component" name)}))
                                    (reset! *open? false))}
            [:strong name] [:small category] [:span doc]
            [:code (pr-str (:schema entry))]])]])]))

(defc <argument-table>
  "Required and optional arguments accept quoted literals or public app-db paths."
  [entry attributes update!]
  [:div.md-component-arguments
   [:table
    [:thead [:tr [:th "Argument"] [:th "Value / app-db path"]]]
    [:tbody
     (for [{:keys [key optional? schema]} (catalog/arguments entry)
           :let [attribute (some #(when (= (name key) (:name %)) %) attributes)
                 raw (or (:value attribute) "")]]
       ^{:key key}
       [:tr
        [:th {:scope "row"}
         (name key) [:small (if optional? "Optional" "Required")]
         [:code (pr-str schema)]]
        [:td
         [:input {:type "text"
                  :aria-label (str (:name entry) " " (name key))
                  :value (if (string? raw) raw "")
                  :placeholder (if optional? "Not set" "Enter a value")
                  :on-change #(let [value (.. % -target -value)
                                    remaining (remove (fn [attr] (= (name key) (:name attr))) attributes)
                                    attributes' (cond-> (vec remaining)
                                                  (not (string/blank? value))
                                                  (conj {:type "mdxJsxAttribute", :name (name key), :value value}))]
                                (update! (clj->js {:attributes attributes'})))}]]])]]
   [:aside.md-component-help
    [:h4 (:name entry)] [:p (:doc entry)]
    [:p "Text is literal. Prefix numbers, booleans, arrays or objects with json:. Prefix public state references with db:."]
    [:code "json:[\"First\",\"Second\"]"]
    [:code "db:[:content :common :title]"]
    [:p "Content and theme paths are readable; account, administration and credential state are excluded."]]])

(defc <component-node>
  "Actual site component preview with a hover/focus argument editor and persistent layout."
  [{:keys [mdastNode]}]
  :let [*open? (r/atom false)
        *draft (r/atom nil)
        *parent-size (r/atom nil)]
  (let [node (js->clj mdastNode :keywordize-keys true)
        entry (get catalog/by-name (model/component-name node))
        invocation (model/invocation node)
        values (components/values invocation)
        geometry (merge (layout/geometry values) @*draft)
        parent-rem (when-let [{:keys [width rem]} @*parent-size] (/ width rem))
        preview-geometry (cond-> geometry
                           (and parent-rem (string/ends-with? (or (:width geometry) "") "%"))
                           (assoc :width (str (* parent-rem (/ (js/parseFloat (:width geometry)) 100)) "rem")))
        invocation' (update invocation :arguments merge
                            (into {} (map (fn [[key value]] [key {:literal value}])) (layout/attributes preview-geometry)))
        update! (editor/useMdastNodeUpdater)
        node-ref (r/use-ref nil)
        commit! (fn [geometry]
                  (let [attrs (remove #(contains? #{"layout-width" "layout-height"} (:name %)) (:attributes node))
                        attrs' (into (vec attrs) (model/attributes (into {} (map (fn [[key value]] [key {:literal value}])) (layout/attributes geometry))))]
                    (update! (clj->js {:attributes attrs'}))))]
    ;; Percentages refer to the editor content box, just like the published
    ;; article. The SDK's floating decorator adds authoring chrome around it.
    (r/use-layout-effect
      (fn []
        (when-let [parent (some-> (.-current node-ref) (.closest ".md-editor-content"))]
          (let [measure! (fn []
                           (let [style (js/getComputedStyle parent)]
                             (reset! *parent-size
                                     {:width (- (.-clientWidth parent)
                                                (js/parseFloat (.-paddingLeft style))
                                                (js/parseFloat (.-paddingRight style)))
                                      :rem (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))})))
                observer (when (exists? js/ResizeObserver) (js/ResizeObserver. measure!))]
            (measure!)
            (when observer (.observe observer parent))
            (fn [] (when observer (.disconnect observer)))))) [])
    [:section.md-component-node
     {:content-editable false
      :ref node-ref
      :data-flow (or (:flow geometry) "block")
      :data-align (or (:align geometry) "left")
      :style (assoc (select-keys (layout/style preview-geometry) [:width])
                    :max-width "calc(100% - 1em - 2 * var(--line-width))")
      :on-mouse-enter #(reset! *open? true)
      :on-mouse-leave #(reset! *open? false)}
     [:header
      [:strong {:title (model/component-name node)} (last (string/split (model/component-name node) #"/"))]
      [:button {:type "button"
                :aria-expanded @*open?
                :aria-label "Edit component arguments"
                :on-click #(swap! *open? not)} "Args"]]
     [components/<render> invocation']
     [resize/<handle>
      {:target #(some-> (.-current node-ref) (.querySelector ".content-widget"))
       :geometry geometry
       :parent-width #(or (:width @*parent-size) 1)
       :preview #(reset! *draft %)
       :commit commit!}]
     [:div.md-component-node__arguments {:class (when @*open? "is-open")}
      [<argument-table> entry (:attributes node) update!]]]))

(def component-node-react (r/reactify-component (fn [props] [<component-node> props])))

(defn descriptors []
  #js [#js {:name "Component"
            :kind "flow"
            :hasChildren false
            :props #js [#js {:name "component", :type "string"}]
            :Editor component-node-react}])

(def media-context (r/create-context {}))

(defn image-props [src alt]
  {"component" "tolgraven.components.content.markdown/<image>"
   "src" src
   "alt" (or alt "Image")})

(defc <image-picker> []
  :let [*open? (r/atom false)
        *query (r/atom "")
        *url (r/atom "")
        *error (r/atom nil)
        *pending? (r/atom false)]
  (let [{:keys [assets library upload!]} (r/use-context media-context)
        active (editor/useCellValue editor/activeEditor$)
        bookmark (r/use-ref nil)
        insert! (editor/usePublisher editor/insertJsx$)
        insert-image! (fn [src alt]
                        (when-let [selection (.-current bookmark)]
                          (.update active
                                   (fn []
                                     (when (and ($getNodeByKey (.. selection -anchor -key))
                                                ($getNodeByKey (.. selection -focus -key)))
                                       ($setSelection (.clone selection))))
                                   #js {:discrete true}))
                        (insert! (clj->js {:kind "flow", :name "Component", :props (image-props src alt)}))
                        (reset! *open? false))]
    [:div.md-image-picker
     [:button {:type "button"
               :aria-expanded @*open?
               :on-click #(do (when active
                                (.read (.getEditorState active)
                                       (fn []
                                         (let [selection ($getSelection)]
                                           (when ($isRangeSelection selection)
                                             (set! (.-current bookmark) (.clone selection)))))))
                              (swap! *open? not)
                              (when library (library :load 0)))} "Image"]
     (when @*open?
       [:div.md-image-picker__menu
        [:label "Find a server image"
         [:input {:type "search", :value @*query, :on-change #(reset! *query (.. % -target -value))}]]
        [:div.md-image-picker__assets
         (for [{:keys [id src]} assets
               :when (string/includes? (string/lower-case (str src)) (string/lower-case @*query))]
           ^{:key id}
           [:button {:type "button", :on-click #(insert-image! src "Image")}
            [:img {:src src, :alt "", :loading "lazy"}] [:span src]])]
        (when library (library :view nil))
        [:label "Image URL"
         [:input {:type "url", :value @*url, :on-change #(reset! *url (.. % -target -value))}]]
        [:button {:type "button", :disabled (string/blank? @*url), :on-click #(insert-image! @*url "Image")} "Insert URL"]
        (when upload!
          [:label "Upload image"
           [:input {:type "file"
                    :accept "image/*"
                    :disabled @*pending?
                    :on-change
                    (fn [event]
                      (when-let [file (aget (.. event -target -files) 0)]
                        (reset! *pending? true)
                        (reset! *error nil)
                        (-> (upload! file)
                            (.then #(insert-image! % (.-name file)))
                            (.catch #(reset! *error (.-message %)))
                            (.finally #(reset! *pending? false)))))}]])
        (when @*pending? [:p {:role "status"} "Preparing image sizes…"])
        (when @*error [:p {:role "alert"} @*error])])]))

(defc <image-layout-toolbar>
  "Convert a portable Markdown image to a canonical responsive component when geometry is needed."
  [{:keys [nodeKey imageSource alt]}]
  (let [active (editor/useCellValue editor/activeEditor$)
        insert! (editor/usePublisher editor/insertJsx$)]
    [:button {:type "button"
              :on-click
              (fn []
                (.update active
                         (fn []
                           (when-let [node ($getNodeByKey nodeKey)]
                             (.selectNext node)
                             (.remove node)))
                         #js {:discrete true})
                (insert! (clj->js {:kind "flow", :name "Component"
                                   :props (assoc (image-props imageSource alt) "layout-width" "20rem")})))}
     "Layout & resize"] ))

(def image-layout-toolbar-react (r/reactify-component (fn [props] [<image-layout-toolbar> props])))

(defc <toolbar> []
  [:> editor/DiffSourceToggleWrapper
   [:> editor/UndoRedo]
   [:> editor/Separator]
   [:> editor/BlockTypeSelect]
   [:> editor/BoldItalicUnderlineToggles]
   [:> editor/StrikeThroughSupSubToggles]
   [:> editor/CodeToggle]
   [:> editor/ListsToggle {:options #js ["bullet" "number" "check"]}]
   [:> editor/CreateLink]
   [<image-picker>]
   [:> editor/InsertTable]
   [:> editor/InsertThematicBreak]
   [:> editor/InsertCodeBlock]
   [<component-picker>]])

(defc <formatting-keymap>
  "Both Ctrl and Cmd formatting shortcuts use the active SDK selection, including nested editors."
  [{:keys [children]}]
  (let [active (editor/useCellValue editor/activeEditor$)]
    [:div {:on-key-down-capture
           (fn [event]
             (when (and active (or (.-ctrlKey event) (.-metaKey event))
                        (not (.-altKey event)) (not (.-shiftKey event))
                        (.-isContentEditable (.-target event)))
               (when-let [format (get {"b" "bold", "i" "italic", "u" "underline"} (.-key event))]
                 (.preventDefault event)
                 (.stopPropagation event)
                 (.dispatchCommand active FORMAT_TEXT_COMMAND format))))}
     children]))

(def formatting-keymap-react (r/reactify-component (fn [props] [<formatting-keymap> props])))
(def keymap-plugin
  (editor/realmPlugin #js {:init (fn [realm]
                                  (.pub realm editor/addEditorWrapper$ formatting-keymap-react))}))

(defn plugins [upload! image-urls]
  #js [(keymap-plugin)
       (editor/headingsPlugin #js {:allowedHeadingLevels #js [1 2 3 4 5 6]})
       (editor/listsPlugin)
       (editor/quotePlugin)
       (editor/thematicBreakPlugin)
       (editor/linkPlugin)
       (editor/linkDialogPlugin)
       (editor/tablePlugin)
       (editor/codeBlockPlugin #js {:defaultCodeBlockLanguage "clojure"})
       (editor/codeMirrorPlugin #js {:codeBlockLanguages #js {:clojure "Clojure"
                                                             :javascript "JavaScript"
                                                             :typescript "TypeScript"
                                                             :css "CSS"
                                                             :json "JSON"
                                                             :bash "Shell"
                                                             :python "Python"
                                                             :sql "SQL"
                                                             :text "Plain text"}})
       (editor/imagePlugin #js {:imageUploadHandler upload!
                                :imageAutocompleteSuggestions (clj->js image-urls)
                                :disableImageResize true
                                :allowSetImageDimensions false
                                :EditImageToolbar image-layout-toolbar-react})
       (editor/jsxPlugin #js {:jsxComponentDescriptors (descriptors)})
       (editor/markdownShortcutPlugin)
       (editor/diffSourcePlugin #js {:viewMode "rich-text"})
       (editor/toolbarPlugin #js {:toolbarContents #(r/as-element [<toolbar>] )})])

(defc <editor>
  "Edit canonical Markdown through the lazy rich-text SDK; only external changes reset SDK content."
  {:spec-schema [:map [:text :string] [:on-change fn?] [:?upload! fn?] [:?image-urls [:vector :string]] [:?assets [:vector :map]] [:?library fn?]]}
  [{:keys [text on-change upload! image-urls assets library]}]
  :let [*error (r/atom nil)
        *overlay (r/atom nil)]
  (let [editor-ref (r/use-ref nil)
        editor-plugins (r/use-memo #(plugins upload! (or image-urls [])) [upload! image-urls])]
    ;; This calls the SDK's public content API, never mutates React-owned markup.
    ;; onChange writes through the parent form's event; selection/history are SDK transient state.
    (r/use-effect
      (fn []
        (when-let [instance (.-current editor-ref)]
          (when-not (= text (.getMarkdown instance)) (.setMarkdown instance text))))
      [text])
    [:r> (r/context-provider media/asset-context) #js {:value (into {} (map (juxt :src identity)) assets)}
     [:r> (r/context-provider media-context) #js {:value {:assets assets, :library library, :upload! upload!}}
     [:div.site-md-editor
     [:div.site-md-editor-overlay {:ref (fn [node] (when node (reset! *overlay node)) nil)}]
     [mdx-editor {:ref editor-ref
                  :markdown text
                  :plugins editor-plugins
                  :contentEditableClassName "md-editor-content"
                  :overlayContainer @*overlay
                  :onChange (fn [markdown initial-normalization?]
                              (when-not initial-normalization?
                                (reset! *error nil)
                                (on-change markdown)))
                  :onError (fn [error]
                             (reset! *error (.-message error)))}]
     (when @*error
       [:p.md-editor-error {:role "alert"}
        "The source contains markup that cannot be edited visually. Switch to source to repair it. " @*error])]]]))
