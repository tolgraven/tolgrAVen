(ns website.modules.markdown-editor.views
  "The SDK owns selection, undo and its editable DOM; application content stays Markdown."
  (:require [website.component.registry]
            [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.modules.markdown-editor.catalog :as catalog]
            [website.modules.markdown-editor.source :as source]
            [website.modules.markdown-editor.uploads :as uploads]
            [website.modules.markdown-editor.code :as code]
            [website.modules.markdown-editor.features :as features]
            [website.markdown.authoring :as model]
            [website.modules.markdown-components.views :as components]
            [website.components.content.layout :as layout]
            [website.components.content.resize :as resize]
            [website.components.image :as image]
            [website.components.content.media :as media]
            [clojure.string :as string]
            ["@mdxeditor/editor" :as editor]
            ["lexical" :refer [$getNodeByKey]]))

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
            [:code (pr-str (or (:schema entry) (:parameters entry)))]])]])]))

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
    [:code (pr-str (:parameters entry))]
    [:p "Text is literal. Use json: for numbers, booleans, arrays or objects; edn: for Clojure keywords, sets or data. Use db: for public state references."]
    [:code "json:[\"First\",\"Second\"]"]
    [:code "edn:[:appear :seen]"]
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
                            (into {} (map (fn [[key value]] [key {:literal value}]))
                                  (layout/attributes (assoc preview-geometry :flow "block"))))
        update! (editor/useMdastNodeUpdater)
        node-ref (r/use-ref nil)
        commit! (fn [geometry']
                  (let [layout-values (layout/attributes geometry')
                        layout-names (set (map name (keys layout-values)))
                        attrs (remove #(contains? layout-names (:name %)) (:attributes node))
                        attrs' (into (vec attrs) (model/attributes (into {} (map (fn [[key value]] [key {:literal value}])) layout-values)))]
                    (update! (clj->js {:attributes attrs'}))))]
    ;; Percentages refer to the editor content box, just like the published
    ;; article. Measure before paint because this width controls the first
    ;; visible floating preview; observers retain ordinary measurement ownership.
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
      :tab-index 0
      :aria-label (str "Embedded component " (model/component-name node))
      :ref node-ref
      :data-flow (or (:flow geometry) "block")
      :data-align (or (:align geometry) "left")
      :style (assoc (select-keys (layout/style preview-geometry) [:width]) :max-width "100%")
      :on-mouse-enter #(reset! *open? true)
      :on-mouse-leave #(reset! *open? false)}
     [:div.md-component-node__chrome {:role "toolbar", :aria-label "Component controls"}
      [:strong {:title (model/component-name node)} (last (string/split (model/component-name node) #"/"))]
      [:button {:type "button"
                :aria-expanded @*open?
                :aria-label "Edit component arguments"
                :on-focus #(reset! *open? true)
                :on-click #(swap! *open? not)} "Args"]
      [resize/<handle>
       {:target #(some-> (.-current node-ref) (.querySelector ".content-widget"))
        :geometry geometry
        :parent-width #(or (:width @*parent-size) 1)
        :preview #(reset! *draft %)
        :commit commit!}]]
     [components/<render> invocation']
     (for [edge [:left :right :top :bottom]]
       ^{:key edge}
       [resize/<handle>
        {:edge edge
         :class "md-resize-edge"
         :target #(some-> (.-current node-ref) (.querySelector ".content-widget"))
         :geometry geometry
         :parent-width #(or (:width @*parent-size) 1)
         :preview #(reset! *draft %)
         :commit commit!}])
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
  {"component" "website.components.content.markdown/<image>"
   "src" src
   "alt" (or alt "Image")})

(defc <image-picker> []
  :let [*open? (r/atom false)
        *query (r/atom "")
        *url (r/atom "")
        *error (r/atom nil)
        *pending? (r/atom false)]
  (let [{:keys [assets library upload!]} (r/use-context media-context)
        {:keys [insert-upload! allow-components?]} (r/use-context uploads/context)
        insert! (editor/usePublisher editor/insertJsx$)
        insert-ordinary! (editor/usePublisher editor/insertImage$)
        insert-image! (fn [src alt]
                        (if (false? allow-components?)
                          (insert-ordinary! (clj->js {:src src, :altText (or alt "Image")}))
                          (insert! (clj->js {:kind "flow", :name "Component", :props (image-props src alt)})))
                        (reset! *open? false))]
    [:div.md-image-picker
     [:button {:type "button"
               :aria-expanded @*open?
               :on-click #(do (swap! *open? not)
                              (when library (library :load 0)))} "Image"]
     (when @*open?
       [:div.md-image-picker__menu
        (when library
          [:label "Find a server image"
           [:input {:type "search", :value @*query, :on-change #(reset! *query (.. % -target -value))}]])
        [:div.md-image-picker__assets
         (for [{:keys [id src] :as asset} assets
               :when (string/includes? (string/lower-case (str src)) (string/lower-case @*query))]
           ^{:key id}
           [:button {:type "button", :on-click #(insert-image! src "Image")}
            [image/<picture> {:src src, :asset asset, :alt "", :loading "lazy", :sizes "6rem", :progressive? false}]
            [:span src]])]
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
                        (if insert-upload!
                          (do (insert-upload! [file]) (reset! *open? false))
                          (do (reset! *pending? true)
                              (reset! *error nil)
                              (-> (upload! file)
                                  (.then #(insert-image! % (.-name file)))
                                  (.catch #(reset! *error (.-message %)))
                                  (.finally #(reset! *pending? false)))))))}]])
        (when @*pending? [:p {:role "status"} "Preparing image…"])
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

(defc <toolbar> [enabled-features]
  [:div.md-editor-toolbar
   (when (enabled-features :history) [:> editor/UndoRedo])
   [:> editor/Separator]
   (when (enabled-features :headings) [:> editor/BlockTypeSelect])
   (when (enabled-features :formatting)
     [:<>
      [:> editor/BoldItalicUnderlineToggles]
      [:> editor/StrikeThroughSupSubToggles]
      [:> editor/CodeToggle]])
   (when (enabled-features :lists) [:> editor/ListsToggle {:options #js ["bullet" "number" "check"]}])
   (when (enabled-features :links) [:> editor/CreateLink])
   (when (enabled-features :images)
     [<image-picker>])
   (when (enabled-features :tables) [:> editor/InsertTable])
   (when (enabled-features :thematic-breaks) [:> editor/InsertThematicBreak])
   (when (enabled-features :code) [:> editor/InsertCodeBlock])
   (when (enabled-features :site-components) [<component-picker>])])

(defc <formatting-keymap>
  "Both Ctrl and Cmd formatting shortcuts use the active SDK selection, including nested editors."
  [{:keys [children]}]
  (let [format! (editor/usePublisher editor/applyFormat$)]
    [:div {:on-key-down-capture
           (fn [event]
             (when (and (or (.-ctrlKey event) (.-metaKey event))
                        (not (.-altKey event)) (not (.-shiftKey event))
                        (.-isContentEditable (.-target event)))
               (when-let [format (get {"b" "bold", "i" "italic", "u" "underline"} (.-key event))]
                 (.preventDefault event)
                 (.stopPropagation event)
                 (format! format))))}
     children]))

(def formatting-keymap-react (r/reactify-component (fn [props] [<formatting-keymap> props])))
(def keymap-plugin
  (editor/realmPlugin #js {:init (fn [realm]
                                  (.pub realm editor/addEditorWrapper$ formatting-keymap-react))}))

(defn plugins [upload! image-urls import-params enabled-features]
  (into-array
    (concat
      (when (enabled-features :formatting) [(keymap-plugin)])
      [
       ((editor/realmPlugin #js {:init (fn [realm] (.pub realm editor/addEditorWrapper$ uploads/controller-react))}))
       (editor/directivesPlugin #js {:directiveDescriptors (uploads/descriptors), :escapeUnknownTextDirectives true})
       (uploads/plugin import-params)
       ]
      (when (enabled-features :headings) [(editor/headingsPlugin #js {:allowedHeadingLevels #js [1 2 3 4 5 6]})])
      (when (enabled-features :lists) [(editor/listsPlugin)])
      (when (enabled-features :quotes) [(editor/quotePlugin)])
      (when (enabled-features :thematic-breaks) [(editor/thematicBreakPlugin)])
      (when (enabled-features :links) [(editor/linkPlugin) (editor/linkDialogPlugin)])
      (when (enabled-features :tables) [(editor/tablePlugin)])
      (when (enabled-features :code) [(editor/codeBlockPlugin #js {:defaultCodeBlockLanguage "", :codeBlockEditorDescriptors code/descriptors})])
      (when (enabled-features :images)
        [(editor/imagePlugin (clj->js (cond-> {:imageAutocompleteSuggestions image-urls
                                :disableImageResize true
                                :allowSetImageDimensions false}
                               (enabled-features :site-components) (assoc :imageUploadHandler upload!
                                                                         :EditImageToolbar image-layout-toolbar-react))))])
      (when (enabled-features :site-components) [(editor/jsxPlugin #js {:jsxComponentDescriptors (descriptors)})])
      [(editor/markdownShortcutPlugin)
       (editor/toolbarPlugin #js {:toolbarContents #(r/as-element [<toolbar> enabled-features])})])))

(defc <editor>
  "Edit canonical Markdown through the lazy rich-text SDK; only external changes reset SDK content."
  {:spec-schema [:map [:text :string] [:on-change fn?] [:?upload! fn?] [:?image-urls [:vector :string]] [:?assets [:vector :map]] [:?library [:maybe fn?]]
                      [:?enabled-features features/schema]
                      [:?source? :boolean] [:?document-id :?string] [:?text-query [:vector :any]] [:?uploads [:vector :map]] [:?uploads-query [:vector :any]] [:?upload-query fn?]
                      [:?render-upload fn?] [:?enqueue! fn?] [:?insert-ready! fn?] [:?on-anchors fn?] [:?on-removed fn?]]}
  [{:keys [text text-query document-id on-change upload! image-urls assets library source? uploads uploads-query upload-query render-upload enqueue! insert-ready! on-anchors on-removed] :as props}]
  :let [*error (r/atom nil)
        *overlay (r/atom nil)]
  (let [editor-ref (r/use-ref nil)
        *pending? (r/use-ref false)
        [input-revision input-changed!] (r/use-state 0)
        *document (r/use-ref document-id)
        *text (when text-query (r/subscribe text-query))
        text' (if *text @*text text)
        on-change' (r/use-callback
                     (fn [markdown & args]
                       (set! (.-current *pending?) true)
                       (input-changed! inc)
                       (apply on-change markdown args))
                     [on-change input-changed!])
        import-params {:text text', :uploads (mapv #(select-keys % [:id :position :image-inserted? :cancelled? :asset]) uploads)}
        enabled-features (features/enabled (:enabled-features props))
        editor-plugins (r/use-memo #(plugins upload! (or image-urls []) import-params enabled-features)
                                  [upload! image-urls import-params enabled-features])]
    ;; This calls the SDK's public content API, never mutates React-owned markup.
    ;; onChange writes through the parent form's event; selection/history are SDK transient state.
    ;; Re-read the managed form query, as source mode does. A delayed parent echo
    ;; cannot reset unacknowledged native input. Keep only a pending flag, never
    ;; historical documents; changing document-id explicitly replaces pending
    ;; content too (including an intentional replacement equal to an older edit).
    ;; Native revisions also reconcile undo back to an unchanged parent value.
    (r/use-effect
      (fn []
        (when source? (set! (.-current *pending?) false))
        (when-let [instance (.-current editor-ref)]
          (let [document-changed? (not= document-id (.-current *document))
                text'' (if *text @*text text')
                external? (or document-changed? (not (.-current *pending?)))
                current-text (.getMarkdown instance)]
            (when (and external? (not= text'' current-text)) (.setMarkdown instance text''))
            (when (or external? (= text'' current-text)) (set! (.-current *pending?) false))
            (set! (.-current *document) document-id))))
      [*text text' document-id source? input-revision])
    [:r> (r/context-provider media/asset-context) #js {:value (into {} (map (juxt :src identity)) assets)}
     [:r> (r/context-provider media-context) #js {:value {:assets assets, :library library, :upload! upload!}}
     [:r> (r/context-provider uploads/context)
      #js {:value (cond-> {:allow-components? (boolean (enabled-features :site-components))
                          :upload-query upload-query
                          :uploads-query uploads-query
                          :render-upload render-upload
                          :enqueue! enqueue!
                          :insert-ready! insert-ready!
                          :on-change on-change'
                          :on-anchors on-anchors
                          :on-removed on-removed}
                    ;; Reusable static hosts need no application subscription.
                    (nil? uploads-query) (assoc :uploads uploads))}
     [:div.site-md-editor
     [:div.site-md-editor-overlay {:ref (fn [node] (when node (reset! *overlay node)) nil)}]
     (if source?
       ^{:key document-id} [source/<source> props]
       ^{:key document-id} [mdx-editor {:ref editor-ref
                  :markdown text'
                  :plugins editor-plugins
                  :contentEditableClassName "md-editor-content md-rendered"
                  :overlayContainer @*overlay
                  :onChange (fn [markdown initial-normalization?]
                              (when-not initial-normalization?
                                (reset! *error nil)
                                nil))
                  :onError (fn [error]
                             (reset! *error (.-message error)))}])
     (when @*error
       [:p.md-editor-error {:role "alert"}
        "The source contains markup that cannot be edited visually. Switch to source to repair it. " @*error])]]]]))
