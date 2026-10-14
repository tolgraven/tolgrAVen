(ns website.modules.markdown-editor.source-model
  "Literal source transformations and the narrow native document/selection boundary."
  (:require [website.modules.markdown-editor.upload-tree :as tree]
            [website.modules.markdown-editor.anchors :as anchors]
            ["lexical" :refer [$getRoot $getSelection $isRangeSelection $isElementNode
                              $createRangeSelection $setSelection]]))

(defn import-tree [text uploads]
  (let [items (sort-by (juxt :position :order)
                      (remove #(or (:chip-dismissed? %) (:cancelled? %)) uploads))
        ;; Source is literal text, including invalid/incomplete Markdown. Native
        ;; inline widgets have no text value and are never authored markers.
        [children' offset']
        (reduce (fn [[children offset] {:keys [id position]}]
                  (let [position' (max offset (min (count text) position))]
                    [(cond-> children
                       (< offset position') (conj {:type "text", :value (subs text offset position')})
                       true (conj (tree/upload-node id position')))
                     position'])) [[] 0] items)]
    {:type "root"
     :children [{:type "paragraph"
                 :children (cond-> children' (< offset' (count text)) (conj {:type "text", :value (subs text offset')}))}]}))

(defn upload-id [^js node]
  (when (= "directive" (.getType node))
    (let [^js data (.getMdastNode node)]
      (when (true? (some-> data .-data .-editorMedia)) (some-> data .-attributes .-id)))))

(defn node-value [^js node offset]
  (let [key (.getKey node)]
    (if ($isElementNode node)
      (let [children (vec (array-seq (.getChildren node)))
            root? (= "root" (.getType node))
            result (reduce (fn [result [index child]]
                             (let [separator (if (and root? (pos? index)) "\n" "")
                                   position (+ offset (count (:text result)) (count separator))
                                   child-result (node-value child position)]
                               (-> result
                                   (update :text str separator (:text child-result))
                                   (update :positions merge (:positions child-result))
                                   (update :segments into (:segments child-result))
                                   (update :elements into (:elements child-result))
                                   (update :offsets conj position))))
                           {:text "", :positions {}, :segments [], :elements [], :offsets []}
                           (map-indexed vector children))
            end (+ offset (count (:text result)))]
        (update result :elements conj {:key key, :offsets (conj (:offsets result) end)}))
      (if-let [id (upload-id node)]
        {:text "", :positions {id offset}, :segments [], :elements []}
        (let [text (.getTextContent node)]
          {:text text
           :positions {}
           :segments (if (= "text" (.getType node)) [{:key key, :from offset, :to (+ offset (count text))}] [])
           :elements []})))))

(defn document [] (node-value ($getRoot) 0))

(defn point-offset [{:keys [segments elements]} ^js point]
  (if (= "text" (.-type point))
    (+ (or (:from (some #(when (= (.-key point) (:key %)) %) segments)) 0) (.-offset point))
    (or (get (:offsets (some #(when (= (.-key point) (:key %)) %) elements)) (.-offset point)) 0)))

(defn selected-range [document]
  (when-let [selection ($getSelection)]
    (when ($isRangeSelection selection)
      {:anchor (point-offset document (.-anchor selection))
       :head (point-offset document (.-focus selection))})))

(defn point-at [{:keys [segments elements text]} position]
  (let [position' (max 0 (min (count text) position))
        segment (or (some #(when (= position' (:from %)) %) segments)
                    (some #(when (<= (:from %) position' (:to %)) %) segments))]
    (if segment
      {:key (:key segment), :offset (- position' (:from segment)), :type "text"}
      (some (fn [{:keys [key offsets]}]
              (when-let [index (last (keep-indexed #(when (= position' %2) %1) offsets))]
                {:key key, :offset index, :type "element"})) elements))))

(defn select-range! [document anchor head]
  (let [selection ($createRangeSelection)
        start (point-at document anchor)
        end (point-at document head)]
    (when (and start end)
      (.. selection -anchor (set (:key start) (:offset start) (:type start)))
      (.. selection -focus (set (:key end) (:offset end) (:type end)))
      ($setSelection selection)
      selection)))

(defn select-drop-point!
  "Measure the browser's drop caret, then update only the native selection model."
  [^js editor event]
  (let [x (.-clientX event)
        y (.-clientY event)
        element (.getRootElement editor)]
    (when (and element (number? x) (number? y) (js/Number.isFinite x) (js/Number.isFinite y))
      (let [range (cond
                    (fn? (.-caretRangeFromPoint js/document)) (.caretRangeFromPoint js/document x y)
                    (fn? (.-caretPositionFromPoint js/document))
                    (when-let [position (.caretPositionFromPoint js/document x y)]
                      (let [range' (.createRange js/document)]
                        (.setStart range' (.-offsetNode position) (.-offset position))
                        (.collapse range' true)
                        range')))]
        (when (and range (.contains element (.-startContainer range)))
          ;; applyDOMRange is Lexical's public measurement adapter. React-owned
          ;; markup is untouched; insertion uses this document selection.
          (.update editor
            (fn []
              (let [selection ($createRangeSelection)]
                (.applyDOMRange selection range)
                ($setSelection selection)))
            #js {:discrete true}))))))

(defn replace-text! [text]
  (let [before (document)
        selected (selected-range before)
        change (anchors/text-change (:text before) text)]
    (when-let [selection (select-range! before (:from change) (:to change))]
      (.insertRawText selection (:inserted change))
      (when selected
        (select-range! (document)
                       (or (anchors/map-position (:anchor selected) [change]) (:from change))
                       (or (anchors/map-position (:head selected) [change]) (:from change)))))))
