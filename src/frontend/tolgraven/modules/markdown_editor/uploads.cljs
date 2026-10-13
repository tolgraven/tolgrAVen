(ns tolgraven.modules.markdown-editor.uploads
  "The SDK's native inline nodes retain pending upload selection without authored markers."
  (:require [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as r]
            [tolgraven.modules.markdown-editor.upload-tree :as tree]
            [tolgraven.modules.blog-editor.anchors :as anchors]
            [tolgraven.modules.blog-editor.files :as files]
            [tolgraven.components.image :as image]
            [tolgraven.components.content.media :as media]
            [tolgraven.components.content.layout :as layout]
            [tolgraven.components.content.resize :as resize]
            ["@mdxeditor/editor" :as editor]
            ["mdast-util-from-markdown" :refer [fromMarkdown]]
            ["mdast-util-to-markdown" :refer [toMarkdown]]
            ["lexical" :refer [$getRoot $getNodeByKey]]))

(def context (r/create-context {}))

(defn upload-node? [^js node]
  (and (editor/$isDirectiveNode node)
       (= tree/upload-name (.-name (.getMdastNode node)))
       (true? (some-> (.getMdastNode node) .-data .-editorMedia))))

(def omit-visitor
  #js {:priority 200
       :testLexicalNode upload-node?
       :visitLexicalNode (fn [^js props]
                          (let [attributes (js->clj (.-attributes (.getMdastNode (.-lexicalNode props))) :keywordize-keys true)]
                            (when-let [node (tree/exported-image attributes)]
                              (when (= "Component" (:name node)) (.. props -actions (registerReferredComponent "Component")))
                              (.. props -actions (appendToParent (.-mdastParent props) (clj->js node))))
                            nil))})

(def collect-visitor
  #js {:priority 300
       :testLexicalNode upload-node?
       :visitLexicalNode (fn [^js props]
                          (.. props -actions (appendToParent (.-mdastParent props)
                                                            (.getMdastNode (.-lexicalNode props)))) )})

(defn native-tree [values]
  (let [[visitors descriptors jsx?] values]
    (js->clj
      (editor/exportLexicalTreeToMdast
        #js {:root ($getRoot)
             :visitors (into-array (cons collect-visitor (array-seq visitors)))
             :jsxComponentDescriptors descriptors
             :jsxIsAvailable jsx?}) :keywordize-keys true)))

(defn positions [values markdown]
  (let [parsed (native-tree values)
        extensions (nth values 3)
        options (nth values 4)]
    (into {} (map (fn [id]
                    (let [prefix (:node (tree/before-upload parsed id))
                          prefix-source (if (seq (:children prefix))
                                          (toMarkdown (clj->js prefix)
                                                      (js/Object.assign #js {:extensions extensions} options)) "")]
                      [id (anchors/common-prefix-length markdown prefix-source)])))
          (tree/upload-ids parsed))))

(defn text-value [node]
  (if (= "text" (:type node)) (:value node)
      (apply str (map text-value (:children node)))))

(defn import-visitor [{:keys [text uploads]}]
  #js {:priority 200
       :uploadAnchors true
       :testNode "root"
       :visitNode
       (fn [^js props]
         (let [parsed (js->clj (.-mdastNode props) :keywordize-keys true)
               transformed (tree/insert-uploads
                             (tree/import-images parsed uploads)
                             (map (juxt :id :position) (remove #(or (:image-inserted? %) (:cancelled? %)) uploads))
                             (fn [node position]
                               (let [start (get-in node [:position :start :offset] 0)
                                     fragment (subs text start (max start (min (count text) position)))]
                                 (count (text-value (js->clj (fromMarkdown fragment) :keywordize-keys true))))))]
           ;; The native visitor action consumes our pure transformed tree;
           ;; the SDK's parsed input stays unchanged.
           (.. props -actions (visitChildren (clj->js transformed) (.-lexicalParent props)))) )})

(def plugin
  (editor/realmPlugin
    #js {:init (fn [realm params]
                 (.pub realm editor/addExportVisitor$ omit-visitor)
                 (.pub realm editor/addImportVisitor$ (import-visitor params)))
         :update (fn [realm params]
                   ;; The SDK owns plugin replacement. Only this visitor is
                   ;; replaced, with ordinary declarative import parameters.
                   (.pub realm editor/importVisitors$
                         (into-array
                           (cons (import-visitor params)
                                 (remove (fn [^js visitor] (.-uploadAnchors visitor)) (array-seq (.getValue realm editor/importVisitors$)))))))}))

(defc ^:private <pending-node>
  "One native media node renders pending/error/ready from the application queue."
  [{:keys [mdastNode lexicalNode ^js parentEditor]}]
  :let [*draft (r/atom nil)
        *open? (r/atom false)
        *fields (r/atom {})]
  (let [{:keys [uploads render-upload insert-ready! on-anchors on-removed]} (r/use-context context)
        realm (editor/useRealm)
        cells (editor/useCellValues editor/exportVisitors$ editor/jsxComponentDescriptors$
                                    editor/jsxIsAvailable$ editor/toMarkdownExtensions$ editor/toMarkdownOptions$)
        values (vec (array-seq cells))
        assets (r/use-context media/asset-context)
        update! (editor/useMdastNodeUpdater)
        id (.. mdastNode -attributes -id)
        item (some #(when (= id (:id %)) %) uploads)
        src (or (.. mdastNode -attributes -src) (get-in item [:asset :src]))
        key (.getKey lexicalNode)
        attributes (js->clj (.-attributes mdastNode) :keywordize-keys true)
        field-values @*fields
        geometry (merge (layout/geometry attributes) @*draft)
        node-ref (r/use-ref nil)
        change! (fn [changes] (update! (clj->js {:attributes (merge attributes changes)})))
        field! (fn [field value]
                 (swap! *fields assoc field value)
                 (when (or (not (#{:layout-width :layout-height} field))
                           (empty? value) (re-matches layout/dimension-pattern value))
                   (update! (clj->js {:attributes (if (empty? value) (dissoc attributes field)
                                                     (assoc attributes field value))}))))
        commit! (fn [geometry'] (change! (layout/attributes geometry')) (reset! *draft nil))]
    (r/use-effect
      (fn []
        ;; A zero-width node may leave canonical Markdown unchanged, so the
        ;; SDK's content signal need not fire. Record its native insertion once.
        (when (and id on-anchors)
          (.read (.getEditorState parentEditor)
                 (fn [] (on-anchors (positions values (.getValue realm editor/markdown$))))))) [key parentEditor on-anchors values realm])
    (r/use-effect
      (fn []
        (when (and item insert-ready! (= :ready (:status item)) (not (:image-inserted? item)))
          (insert-ready! item
                         (fn [result!]
                           ;; The SDK updater changes this existing document
                           ;; node. No neighboring image or DOM node is inserted.
                           (let [exists? (.read (.getEditorState parentEditor)
                                                (fn [] (some? ($getNodeByKey key))))]
                             (when exists?
                               (change! {:id id, :src (get-in item [:asset :src]), :alt (:name item)}))
                             (result! (boolean
                                        (and exists?
                                             (.read (.getEditorState parentEditor)
                                                    (fn [] (= (get-in item [:asset :src])
                                                              (some-> ($getNodeByKey key) .getMdastNode .-attributes .-src)))))))))))
        (when (:cancelled? item)
          ;; The SDK removal hook schedules an unguarded transaction. Guard
          ;; inside the transaction so effect replay cannot remove twice.
          (.update parentEditor
                   (fn [] (when-let [node ($getNodeByKey key)] (.selectNext node) (.remove node)))
                   #js {:discrete true})
          (when on-removed (on-removed id))))
      [item key parentEditor insert-ready! update! src id attributes on-removed])
    [:span.md-media-node.content-widget {:content-editable false
                         :data-upload-id id
                         :ref node-ref
                         :tab-index 0
                         :data-flow (or (:flow geometry) "block")
                         :data-align (or (:align geometry) "left")
                         :data-height-mode (if (#{nil "auto" "fit"} (:height geometry)) "auto" "fixed")
                         :style (layout/style geometry)}
     (when (seq src)
       [:<>
        [:span.md-component-node__chrome
         [:strong "Image"]
         [:button {:type "button", :on-click #(swap! *open? not), :aria-expanded @*open?} "Args"]
         [resize/<handle> {:target #(.-current node-ref), :geometry geometry, :preview #(reset! *draft %), :commit commit!}]]
        [image/<picture> (cond-> {:src src, :alt (or (:alt attributes) (:name item) "")}
                           (or (:asset item) (get assets src)) (assoc :asset (or (:asset item) (get assets src))))]
        (for [edge [:left :right :top :bottom]]
          ^{:key edge} [resize/<handle> {:edge edge, :class "md-resize-edge", :target #(.-current node-ref)
                                       :geometry geometry, :preview #(reset! *draft %), :commit commit!}])
        (when @*open?
          [:span.md-component-node__arguments.is-open
           (for [[field label] [[:src "Source URL"] [:alt "Alternative text"] [:title "Title"] [:layout-width "Width"] [:layout-height "Height"]]]
             ^{:key field} [:label label [:input {:type "text", :value (get field-values field (get attributes field ""))
                                                :on-change #(field! field (.. % -target -value))}]])
           (for [[field label choices] [[:layout-align "Alignment" ["left" "center" "right"]]
                                       [:layout-flow "Text flow" ["block" "left" "right"]]]]
             ^{:key field} [:label label [:select {:value (get attributes field (first choices))
                                                  :on-change #(change! {field (.. % -target -value)})}
                                          (for [choice choices] ^{:key choice} [:option {:value choice} choice])]])])])
     (when (and item render-upload (not (:chip-dismissed? item)) (not (:cancelled? item))) (render-upload item))]))

(def pending-react (r/reactify-component (fn [props] [<pending-node> props])))

(defn descriptors []
  #js [#js {:name tree/upload-name
            :testNode (fn [^js node]
                        (let [^js data (.-data node)]
                          (and (= "textDirective" (.-type node)) (= tree/upload-name (.-name node))
                               data (true? (.-editorMedia data)))))
            :hasChildren false
            :Editor pending-react}])

(defc ^:private <controller>
  "Observe native selection/changes and expose insertion to paste/drop and the image picker."
  [{:keys [children]}]
  (let [{:keys [enqueue! on-change uploads] :as data} (r/use-context context)
        pending-ids (mapv :id (remove :chip-dismissed? uploads))
        insert-directive! (editor/usePublisher editor/insertDirective$)
        root (editor/useCellValue editor/rootEditor$)
        realm (editor/useRealm)
        cells (editor/useCellValues editor/exportVisitors$ editor/jsxComponentDescriptors$
                                    editor/jsxIsAvailable$ editor/toMarkdownExtensions$ editor/toMarkdownOptions$)
        values (vec (array-seq cells))
        insert! (r/use-callback
                  (fn [files]
                    (when (and enqueue! (seq files))
                      (let [additions (mapv (fn [file] {:id (str (random-uuid)), :name (.-name file), :file file}) files)]
                        (enqueue! additions 0 0 true)
                        (doseq [{:keys [id]} additions]
                          (insert-directive! (clj->js (tree/upload-node id 0))))))) [insert-directive! enqueue!])]
    (r/use-effect
      (fn []
        (when root
          ;; The SDK already exports only content changes. Observe its Markdown
          ;; cell, preserving its normalization/mute guards, instead of exporting
          ;; a second document for every selection transaction.
          (.sub realm editor/markdown$
                (fn [markdown]
                  (when (and on-change (some? (.getRootElement root))
                             (not (.getValue realm editor/initialMarkdownNormalize$))
                             (not (.getValue realm editor/muteChange$)))
                    (if enqueue!
                      (.read (.getEditorState root)
                             (fn [] (on-change markdown nil (if (seq pending-ids) (positions values markdown) {}))))
                      (on-change markdown)))
                  nil)))) [root realm on-change enqueue! values pending-ids])
    [:r> (r/context-provider context) #js {:value (assoc data :insert-upload! insert!)}
     [:div.md-rich-upload-host
     {:on-paste-capture (fn [event]
                         (let [files (files/image-files (.. event -clipboardData -files))]
                           (when (seq files) (.preventDefault event) (.stopPropagation event) (insert! files))))
      :on-drop-capture (fn [event]
                        (let [files (files/image-files (.. event -dataTransfer -files))]
                          (when (seq files) (.preventDefault event) (.stopPropagation event) (insert! files))))}
      children]]))

(def controller-react (r/reactify-component (fn [props] [<controller> props])))
