(ns website.modules.markdown-editor.uploads
  "The SDK's native inline nodes retain pending upload selection without authored markers."
  (:require [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.modules.markdown-editor.upload-tree :as tree]
            [website.modules.markdown-editor.source-model :as source-model]
            [website.modules.blog-editor.anchors :as anchors]
            [website.modules.blog-editor.files :as files]
            [website.components.image :as image]
            [website.components.content.media :as media]
            [website.components.content.layout :as layout]
            [website.components.content.resize :as resize]
            ["@mdxeditor/editor" :as editor]
            ["mdast-util-from-markdown" :refer [fromMarkdown]]
            ["mdast-util-to-markdown" :refer [toMarkdown]]
            ["lexical" :refer [$getRoot $getNodeByKey]]))

(def context
  ;; Only per-editor native capabilities/query descriptors cross SDK ancestry.
  ;; Application upload values are read by each node's exact subscription.
  (r/create-context {}))

(defn upload-node? [^js node]
  (and (editor/$isDirectiveNode node)
       (= tree/upload-name (.-name (.getMdastNode node)))
       (true? (some-> (.getMdastNode node) .-data .-editorMedia))))

(defn media-id-present? [^js node id]
  (or (and (upload-node? node) (= id (some-> (.getMdastNode node) .-attributes .-id)))
      (and (fn? (.-getChildren node))
           (some #(media-id-present? % id) (array-seq (.getChildren node))))))

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

(defn import-visitor [{:keys [text uploads source?]}]
  #js {:priority 200
       :uploadAnchors true
       :testNode "root"
       :visitNode
       (fn [^js props]
         (let [parsed (js->clj (.-mdastNode props) :keywordize-keys true)
               transformed (if source?
                             (source-model/import-tree text uploads)
                             (tree/insert-uploads
                             (tree/import-images parsed uploads)
                             (map (juxt :id :position) (remove #(or (:image-inserted? %) (:cancelled? %)) uploads))
                             (fn [node position]
                               (let [start (get-in node [:position :start :offset] 0)
                                     fragment (subs text start (max start (min (count text) position)))]
                                 (count (text-value (js->clj (fromMarkdown fragment) :keywordize-keys true)))))))]
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
  (let [{:keys [uploads upload-query source? render-upload insert-ready! on-anchors on-removed allow-components?]} (r/use-context context)
        layout-enabled? (not (false? allow-components?))
        realm (editor/useRealm)
        cells (editor/useCellValues editor/exportVisitors$ editor/jsxComponentDescriptors$
                                    editor/jsxIsAvailable$ editor/toMarkdownExtensions$ editor/toMarkdownOptions$)
        values (vec (array-seq cells))
        assets (r/use-context media/asset-context)
        update! (editor/useMdastNodeUpdater)
        id (.. mdastNode -attributes -id)
        item (if (and id upload-query)
               @(r/subscribe (upload-query id))
               (some #(when (= id (:id %)) %) uploads))
        src (when-not source? (or (.. mdastNode -attributes -src) (get-in item [:asset :src])))
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
                 (fn [] (on-anchors (if source? (:positions (source-model/document))
                                     (positions values (.getValue realm editor/markdown$)))))))) [key parentEditor on-anchors values realm source?])
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
         (when layout-enabled?
           [resize/<handle> {:target #(.-current node-ref), :geometry geometry, :preview #(reset! *draft %), :commit commit!}])]
        [image/<picture> (cond-> {:src src, :alt (or (:alt attributes) (:name item) "")}
                           (or (:asset item) (get assets src)) (assoc :asset (or (:asset item) (get assets src))))]
        (when layout-enabled? (for [edge [:left :right :top :bottom]]
          ^{:key edge} [resize/<handle> {:edge edge, :class "md-resize-edge", :target #(.-current node-ref)
                                       :geometry geometry, :preview #(reset! *draft %), :commit commit!}]))
        (when @*open?
          [:span.md-component-node__arguments.is-open
           (for [[field label] (cond-> [[:src "Source URL"] [:alt "Alternative text"] [:title "Title"]]
                                layout-enabled? (into [[:layout-width "Width"] [:layout-height "Height"]]))]
             ^{:key field} [:label label [:input {:type "text", :value (get field-values field (get attributes field ""))
                                                :on-change #(field! field (.. % -target -value))}]])
           (when layout-enabled? (for [[field label choices] [[:layout-align "Alignment" ["left" "center" "right"]]
                                       [:layout-flow "Text flow" ["block" "left" "right"]]]]
             ^{:key field} [:label label [:select {:value (get attributes field (first choices))
                                                  :on-change #(change! {field (.. % -target -value)})}
                                          (for [choice choices] ^{:key choice} [:option {:value choice} choice])]]))])])
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
  (let [{:keys [enqueue! on-change on-removed source? uploads uploads-query] :as data} (r/use-context context)
        uploads' (if uploads-query @(r/subscribe uploads-query) uploads)
        pending-ids (mapv :id (remove :chip-dismissed? uploads'))
        insert-directive! (editor/usePublisher editor/insertDirective$)
        root (editor/useCellValue editor/rootEditor$)
        realm (editor/useRealm)
        cells (editor/useCellValues editor/exportVisitors$ editor/jsxComponentDescriptors$
                                    editor/jsxIsAvailable$ editor/toMarkdownExtensions$ editor/toMarkdownOptions$)
        values (vec (array-seq cells))
        insert! (r/use-callback
                  (fn [files]
                    (when (and enqueue! (seq files))
                      (let [additions (mapv (fn [file] {:id (str (random-uuid)), :name (.-name file), :file file}) files)
                            selection (when (and source? root)
                                        (.read (.getEditorState root) #(source-model/selected-range (source-model/document))))
                            start (min (or (:anchor selection) 0) (or (:head selection) 0))
                            end (max (or (:anchor selection) 0) (or (:head selection) 0))]
                        (enqueue! additions start end (not source?))
                        (doseq [{:keys [id]} additions]
                          (insert-directive! (clj->js (tree/upload-node id start))))))) [insert-directive! enqueue! root source?])]
    (r/use-effect
      (fn []
        (when (and root on-removed)
          ;; Deleting a zero-width pending node need not change markdown$.
          ;; Native document mutations cover deletion without serializing on
          ;; selection updates, and cleanup never reports a document deletion.
          (.registerMutationListener root editor/DirectiveNode
            (fn [mutations details]
              (let [removed (.read (.-prevEditorState details)
                              (fn []
                                (into []
                                  (keep (fn [[key kind]]
                                          (when (= kind "destroyed")
                                            (when-let [node ($getNodeByKey key)]
                                              (when (upload-node? node)
                                                (some-> (.getMdastNode node) .-attributes .-id))))))
                                  (array-seq (js/Array.from mutations)))))]
                (.read (.getEditorState root)
                  (fn []
                    (doseq [id removed :when (not (media-id-present? ($getRoot) id))]
                      (on-removed id))))))
            #js {:skipInitialization true}))) [root on-removed])
    (r/use-effect
      (fn []
        (when root
          ;; The SDK already exports only content changes. Observe its Markdown
          ;; cell, preserving its normalization/mute guards, instead of exporting
          ;; a second document for every selection transaction.
          (if source?
            (.registerUpdateListener root
              (fn [update]
                (when (and on-change (or (pos? (.. update -dirtyElements -size)) (pos? (.. update -dirtyLeaves -size))))
                  (.read (.-editorState update)
                    (fn []
                      (let [document (source-model/document)]
                        (on-change (:text document) nil (:positions document))))))
                ;; Lexical accepts an optional cleanup from update listeners;
                ;; an application callback's result must never become cleanup.
                nil))
            (.sub realm editor/markdown$
                (fn [markdown]
                  (when (and on-change (some? (.getRootElement root))
                             (not (.getValue realm editor/initialMarkdownNormalize$))
                             (not (.getValue realm editor/muteChange$)))
                    (if enqueue!
                      (.read (.getEditorState root)
                             (fn [] (on-change markdown nil (if (seq pending-ids) (positions values markdown) {}))))
                      (on-change markdown)))
                  nil))))) [root realm on-change enqueue! values pending-ids source?])
    [:r> (r/context-provider context) #js {:value (assoc data :insert-upload! insert!)}
     [:div.md-rich-upload-host
     {:on-drag-over-capture (fn [event]
                             (when-let [transfer (.-dataTransfer event)]
                               (when (files/image-transfer? transfer) (.preventDefault event))))
      :on-paste-capture (fn [event]
                         (let [files (files/image-files (.. event -clipboardData -files))]
                           (when (seq files) (.preventDefault event) (.stopPropagation event) (insert! files))))
      :on-drop-capture (fn [event]
                        (let [files (files/image-files (.. event -dataTransfer -files))]
                          (when (seq files)
                            (.preventDefault event)
                            (.stopPropagation event)
                            (when (and source? root) (source-model/select-drop-point! root event))
                            (insert! files))))}
      children]]))

(def controller-react (r/reactify-component (fn [props] [<controller> props])))
