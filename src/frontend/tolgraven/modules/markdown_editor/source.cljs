(ns tolgraven.modules.markdown-editor.source
  "Controlled literal Markdown uses the installed native document/history and inline media nodes.
   Text/on-change and disposal remain the replacement boundary for a future Monaco adapter."
  (:require [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as r]
            [tolgraven.modules.markdown-editor.source-model :as model]
            [tolgraven.modules.markdown-editor.uploads :as uploads]
            [tolgraven.modules.blog-editor.files :as files]
            ["@mdxeditor/editor" :as editor]
            ["lexical" :refer [$getRoot $getNodeByKey SKIP_DOM_SELECTION_TAG]]))

(def native-editor (r/adapt-react-class editor/MDXEditor))

(defn media-keys [^js node]
  (concat (when (model/upload-id node) [(.getKey node)])
          (when (fn? (.-getChildren node)) (mapcat media-keys (array-seq (.getChildren node))))))

(defc ^:private <controlled-source> [{:keys [children]}]
  (let [{:keys [source-text source-text-query uploads uploads-query]} (r/use-context uploads/context)
        *text (when source-text-query (r/subscribe source-text-query))
        *jobs (when uploads-query (r/subscribe uploads-query))
        text (or (if *text @*text source-text) "")
        jobs (if *jobs @*jobs uploads)
        live-ids (set (map :id (remove #(or (:chip-dismissed? %) (:cancelled? %)) jobs)))
        root (editor/useCellValue editor/rootEditor$)]
    (r/use-effect
      (fn []
        (when root
          ;; Update the native document model, not React-owned markup. A
          ;; bounded text transaction retains the user's selection and undo.
          (.update root
            (fn []
              ;; Accepted form transactions can advance between render and
              ;; this passive effect. Reconcile the owned query's current
              ;; value, never overwrite newer typing with an earlier echo.
              (let [text' (or (if *text @*text text) "")
                    jobs' (if *jobs @*jobs jobs)
                    live-ids' (set (map :id (remove #(or (:chip-dismissed? %) (:cancelled? %)) jobs')))]
                (when-not (= text' (:text (model/document))) (model/replace-text! text'))
                (doseq [key (media-keys ($getRoot))
                        :let [node ($getNodeByKey key)]
                        :when (not (live-ids' (model/upload-id node)))]
                  (.remove node))))
            (cond-> #js {:discrete true}
              (not (some-> (.getRootElement root) (.contains (.-activeElement js/document))))
              (doto (aset "tag" SKIP_DOM_SELECTION_TAG)))))) [root *text *jobs text live-ids])
    children))

(def controlled-source-react (r/reactify-component (fn [props] [<controlled-source> props])))
(def control-plugin
  (editor/realmPlugin #js {:init (fn [realm]
                                  (.pub realm editor/addEditorWrapper$ uploads/controller-react)
                                  (.pub realm editor/addEditorWrapper$ controlled-source-react))}))

(defc ^:private <attachments> []
  (let [{:keys [insert-upload! enqueue!]} (r/use-context uploads/context)]
    (when enqueue!
      [:label.blog-image-editor__attach "Attach images "
       [:input {:type "file"
                :accept "image/*"
                :multiple true
                :on-change (fn [event]
                             (when insert-upload! (insert-upload! (files/image-files (.. event -target -files))))
                             (set! (.. event -target -value) ""))}]])))

(defc <source>
  {:spec-schema [:map [:text :string] [:on-change fn?] [:?text-query [:vector :any]]
                      [:?uploads [:vector :map]] [:?render-upload fn?] [:?enqueue! fn?]]}
  [{:keys [text text-query uploads] :as props}]
  (let [inherited (r/use-context uploads/context)
        capabilities (cond-> (merge inherited (select-keys props [:on-change :render-upload :enqueue! :on-anchors :on-removed :insert-ready!]))
                       (nil? (:uploads-query inherited)) (assoc :uploads uploads))
        params {:text text, :uploads uploads, :source? true}
        plugins (r/use-memo
                  #(into-array [(control-plugin)
                                (editor/directivesPlugin #js {:directiveDescriptors (uploads/descriptors), :escapeUnknownTextDirectives true})
                                (uploads/plugin params)
                                (editor/toolbarPlugin #js {:toolbarContents (fn [] (r/as-element [<attachments>]))})])
                  [params])]
    [:r> (r/context-provider uploads/context)
     #js {:value (cond-> (assoc capabilities :source? true, :source-text-query text-query)
                   (nil? text-query) (assoc :source-text text))}
     [:div.md-source-editor
      [native-editor {:markdown text
                      :plugins plugins
                      :contentEditableClassName "md-source-content"
                      :spellCheck false
                      :onChange (fn [_ _] nil)}]]]))
