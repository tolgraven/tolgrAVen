(ns tolgraven.modules.markdown-editor.source
  "A stable CodeMirror view owns its editable DOM; React owns the inline widget portals.
   Unlike the SDK source pane, external upload insertion preserves selection/history."
  (:require [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as r]
            [tolgraven.modules.blog-editor.anchors :as anchors]
            [tolgraven.modules.blog-editor.files :as files]
            ["@codemirror/state" :refer [EditorState Compartment Annotation]]
            ["@codemirror/view" :refer [EditorView Decoration WidgetType]]
            ["@codemirror/lang-markdown" :refer [markdown]]
            ["codemirror" :refer [basicSetup]]))

(def external-change (.define Annotation))

(defn widget [id report!]
  ;; CodeMirror's WidgetType protocol needs a native host. Its contents are a
  ;; normal React portal, so this adapter never writes application markup/styles.
  (let [instance (js/Object.create (.-prototype WidgetType))]
    (set! (.-toDOM instance)
          (fn [] (let [host (.createElement js/document "span")]
                   (report! id host)
                   host)))
    (set! (.-eq instance) (fn [other] (identical? instance other)))
    (set! (.-ignoreEvent instance) (fn [] true))
    (set! (.-destroy instance) (fn [host] (report! id nil host)))
    instance))

(defn decorations [uploads length widgets report!]
  (.set Decoration
        (clj->js
          (mapv (fn [{:keys [id position order]}]
                  (let [instance (or (get @widgets id)
                                     (let [created (widget id report!)]
                                       (swap! widgets assoc id created)
                                       created))]
                    (.range (.widget Decoration #js {:widget instance, :side (+ -1000 order)})
                            (max 0 (min length position)))))
                (sort-by (juxt :position :order) (remove #(or (:chip-dismissed? %) (:cancelled? %)) uploads)))) true))

(defn change-listener [on-change]
  (.of (.-updateListener EditorView)
       (fn [^js update]
         (when (and (.-docChanged update)
                    (not-any? #(.annotation % external-change) (array-seq (.-transactions update))))
           (let [changes (atom [])]
             (.iterChanges (.-changes update)
                           (fn [from to _ _ inserted]
                             (swap! changes conj {:from from, :to to, :inserted (.toString inserted)})))
             (on-change (.toString (.. update -state -doc)) @changes)))
         nil)))

(defc <source>
  {:spec-schema [:map [:text :string] [:on-change fn?] [:?uploads [:vector :map]]
                      [:?render-upload fn?] [:?enqueue! fn?]]}
  [{:keys [text on-change uploads render-upload enqueue!] :as props}]
  :let [*hosts (r/atom {})
        *widgets (atom {})]
  (let [host-ref (r/use-ref nil)
        view-ref (r/use-ref nil)
        compartment (r/use-memo #(Compartment.) [])
        listener-compartment (r/use-memo #(Compartment.) [])
        report! (r/use-callback (fn [id host & [previous]]
                                 (if host (swap! *hosts assoc id host)
                                     (swap! *hosts #(if (identical? previous (get % id)) (dissoc % id) %)))) [])
        hosts @*hosts
        transfer! (fn [event transfer]
                    (let [files (files/image-files (.-files transfer))
                          view (.-current view-ref)]
                      (when (and enqueue! view (seq files))
                        (.preventDefault event)
                        (let [range (.. view -state -selection -main)]
                          (enqueue! files (.-from range) (.-to range) false))))) ]
    (r/use-effect
      (fn []
        ;; Initial document/configuration belong to the native view lifetime.
        ;; Later text, decorations and callbacks have separate configuration
        ;; effects, so changes never reconstruct the view or its undo history.
        (let [state (.create EditorState
                             #js {:doc text
                                  :extensions #js [basicSetup (markdown) (.-lineWrapping EditorView)
                                                   (.of listener-compartment (change-listener on-change))
                                                   (.of compartment
                                                        (.of (.-decorations EditorView)
                                                             (decorations uploads (count text) *widgets report!)))]})
              view (EditorView. #js {:state state, :parent (.-current host-ref)})]
          (set! (.-current view-ref) view)
          (fn [] (.destroy view) (reset! *widgets {}) (set! (.-current view-ref) nil)))) [])
    (r/use-effect
      (fn []
        (when-let [view (.-current view-ref)]
          (.dispatch view #js {:effects (.reconfigure listener-compartment (change-listener on-change))}))) [on-change])
    (r/use-effect
      (fn []
        (when-let [view (.-current view-ref)]
          (let [live-ids (set (map :id (remove #(or (:chip-dismissed? %) (:cancelled? %)) uploads)))
                before (.toString (.. view -state -doc))
                change (when-not (= before text) (anchors/text-change before text))]
            (swap! *widgets select-keys live-ids)
            (.dispatch view
                       (cond-> #js {:effects (.reconfigure compartment
                                                         (.of (.-decorations EditorView)
                                                              (decorations uploads (count text) *widgets report!)))}
                         change (doto (aset "changes" (clj->js {:from (:from change), :to (:to change), :insert (:inserted change)}))
                                      (aset "annotations" (.of external-change true)))))))) [text uploads])
    [:div.md-source-editor
     {:on-paste-capture #(transfer! % (.-clipboardData %))
      :on-drop-capture #(transfer! % (.-dataTransfer %))
      :on-drag-over #(when (some (fn [item] (= "file" (.-kind item))) (array-seq (.. % -dataTransfer -items)))
                       (.preventDefault %))}
     [:div {:ref host-ref}]
     (when enqueue!
       [:label.blog-image-editor__attach "Attach images "
        [:input {:type "file", :accept "image/*", :multiple true
                 :on-change (fn [event]
                              (let [view (.-current view-ref)
                                    range (.. view -state -selection -main)
                                    files (vec (array-seq (.. event -target -files)))]
                                (enqueue! files (.-from range) (.-to range) false)
                                (set! (.. event -target -value) "")))}]])
     (when render-upload
       (for [{:keys [id] :as item} uploads
             :let [host (get hosts id)]
             :when host]
         ^{:key id} (r/create-portal (r/as-element (render-upload item)) host id)))]))
