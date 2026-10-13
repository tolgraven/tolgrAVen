(ns tolgraven.blog-editor-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as string]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc]]
            [re-frame.db :as rfdb]
            [re-frame.registrar :as registrar]
            [tolgraven.test-support :as support]
            [tolgraven.supabase.runtime :as client]
            [tolgraven.modules.blog.subs]
            [tolgraven.events]
            [tolgraven.subs]
            [tolgraven.modules.blog-editor.model :as model]
            [tolgraven.modules.blog-editor.anchors :as anchors]
            [tolgraven.modules.blog-editor.editor :as editor]
            [tolgraven.modules.blog-editor.files :as file-boundary]
            [tolgraven.modules.blog-editor.views :as views]
            [tolgraven.modules.markdown-editor.source :as source]
            [tolgraven.components.content.upload :as upload]
            [tolgraven.schema.dsl :as dsl]
            ["@codemirror/view" :refer [EditorView]]
            ["lexical" :refer [getNearestEditorFromDOMNode $getNearestNodeFromDOMNode $createNodeSelection $setSelection KEY_BACKSPACE_COMMAND]]
            [malli.core :as m]))

(def files [{:id "first", :name "first.png", :file :first-file}
            {:id "second", :name "second.png", :file :second-file}])

(defn restore-fixture-db! [before]
  ;; Loaded JavaScript/CSS cannot be unloaded by resetting app-db. Keep their
  ;; actual readiness paired with the runtime cache while restoring owned data.
  (let [current @rfdb/app-db]
    (reset! rfdb/app-db (-> before
                           (assoc :loader (:loader current))
                           (assoc-in [:state :init :scope] (get-in current [:state :init :scope]))))))

(deftest attachments-preserve-cursor-order-and-concurrent-draft-edits
  (let [{:keys [text uploads]} (model/insert-files "before SELECTED after" 7 15 {} files)
        text' (str "New intro " text "\nAnother edit")
        uploads' (model/map-uploads uploads [{:from 0, :to 0, :inserted "New intro "}])
        first-upload (uploads' "first")
        second-upload (uploads' "second")
        second-ready (model/complete-upload text' second-upload {:src "https://media.test/second.png"})
        both-ready (model/complete-upload second-ready first-upload {:src "https://media.test/first.png"})]
    (is (m/validate (dsl/->malli model/uploads-schema) uploads))
    (is (= "before  after" text))
    (is (= "first" (:id (model/next-upload uploads))))
    (is (nil? (model/next-upload (assoc-in uploads ["first" :status] :processing))))
    (is (= "New intro before ![first.png](https://media.test/first.png)![second.png](https://media.test/second.png) after\nAnother edit" both-ready))
    (is (model/unresolved? text uploads))
    (is (not (model/unresolved? both-ready {})))))

(deftest sdk-uploads-share-the-queue-without-mutating-source-markers
  (let [{:keys [text uploads]} (model/insert-files "draft unchanged" 0 0 {}
                                                  [(assoc (first files) :sdk? true :owner "rich-editor")])
        upload (get uploads "first")]
    (is (= "draft unchanged" text))
    (is (= "draft unchanged" (model/complete-upload text upload {:src "https://media.test/ready.png"})))
    (is (= "draft unchanged" (model/cancel-upload text upload)))
    (is (= "first" (:id (model/next-upload uploads))))))

(deftest cancelled-attachments-never-reappear-or-overwrite-later-edits
  (let [{:keys [text uploads]} (model/insert-files "draft" 5 5 {} (take 1 files))
        upload (uploads "first")
        cancelled (model/cancel-upload text upload)
        edited (str cancelled " edited")]
    (is (= "draft" cancelled))
    (is (= edited (model/cancel-upload edited upload)))
    (is (not (model/unresolved? text {})))
    (is (= "first" (:id (model/next-upload (assoc-in uploads ["first" :status] :queued)))))))

(deftest markdown-escapes-filenames-and-inserts-literal-dollar-text
  (let [upload {:name "a[bracket]\\file$&.png", :position 0}
        output (model/complete-upload "" upload {:src "https://media.test/image.png"})]
    (is (= "![a\\[bracket\\]\\\\file$&.png](https://media.test/image.png)" output))
    (is (= "![line break.png](https://media.test/image.png)"
           (model/image-markdown "line\nbreak.png" "https://media.test/image.png")))))

(deftest file-boundary-accepts-images-and-preserves-ordinary-paste-drop
  (let [png (js/File. #js ["sample"] "photo.png" #js {:type "image/png"})
        avif (js/File. #js ["sample"] "PHOTO.AVIF" #js {:type ""})
        text (js/File. #js ["sample"] "notes.txt" #js {:type "text/plain"})]
    (is (file-boundary/image-file? png))
    (is (file-boundary/image-file? avif))
    (is (not (file-boundary/image-file? text)))
    (is (= [png avif] (file-boundary/image-files #js [png text avif])))))

(def asset
  (let [src "https://media.test/image.png"
        rendition (fn [format mime]
                    {:src (str "https://media.test/image." format)
                     :width 800
                     :height 600
                     :bytes 1024
                     :mime mime})]
    {:id "asset-id"
     :src src
     :width 800
     :height 600
     :renditions {:original (rendition "png" "image/png")
                  :webp [(rendition "webp" "image/webp")]
                  :avif [(rendition "avif" "image/avif")]}}))

(deftest repeated-canonical-sources-have-distinct-stable-preview-generations
  (let [first-upload {:id "first", :attempt 1, :asset asset}
        second-upload {:id "second", :attempt 1, :asset asset}
        generation (model/preview-generations [first-upload second-upload])]
    (is (= generation (model/preview-generations [second-upload first-upload])))
    (is (not= (model/preview-generations [first-upload]) generation))
    (is (not= generation (model/preview-generations [(assoc first-upload :preview-attempt 1) second-upload])))))

(defn transfer! [textarea kind file]
  ;; Native File/clipboard/drop boundaries exercise the actual Reagent handlers.
  (let [transfer (js/DataTransfer.)
        event (js/Event. kind #js {:bubbles true, :cancelable true})]
    (.add (.-items transfer) file)
    (js/Object.defineProperty event (if (= kind "paste") "clipboardData" "dataTransfer")
                              #js {:value transfer})
    (.dispatchEvent textarea event)
    event))

(defn source-view [element]
  (some-> (.querySelector element ".cm-editor") (EditorView.findFromDOM)))

(defn source-text [element]
  (some-> (source-view element) .-state .-doc .toString))

(deftest source-view-retains-selection-history-and-chips-across-callback-and-progress-updates
  (async done
    (let [element (.createElement js/document "div")
          *root (atom nil)
          *text (rf/atom "Hello")
          *item (rf/atom {:id "native-pending", :name "photo.png", :position 2, :order 0, :status :queued})
          fixture (fn []
                    [source/<source> {:text @*text, :uploads [@*item]
                                      ;; A new callback on each render exercises
                                      ;; listener reconfiguration independently
                                      ;; of the editor/undo lifetime.
                                      :on-change (fn [text _] (reset! *text text))
                                      :render-upload (fn [item] [upload/<upload-slot> item])}])]
      (.appendChild (.-body js/document) element)
      (-> (support/create-root! element)
          (.then (fn [root] (reset! *root root) (support/render! root [fixture])))
          (.then (fn [_] (support/wait-for! #(.querySelector element ".upload-chip"))))
          (.then (fn [_]
                   (let [view (source-view element)]
                     (.dispatch view #js {:changes #js {:from 2, :insert "x"}, :selection #js {:anchor 3}})
                     (swap! *item assoc :status :uploading :progress {:loaded 40, :total 100})
                     (-> (support/settle!)
                         (.then (fn [_]
                                  (is (identical? view (source-view element)))
                                  (is (= 3 (.. view -state -selection -main -head)))
                                  (is (= "Hexllo" (source-text element)))
                                  (is (.querySelector element ".upload-chip progress"))
                                  (.focus view)
                                  (.dispatchEvent (.querySelector element ".cm-content")
                                                  (js/KeyboardEvent. "keydown" #js {:key "z", :code "KeyZ", :metaKey true
                                                                                    :bubbles true, :cancelable true}))))))))
          (.then (fn [_] (support/wait-for! #(= "Hello" (source-text element)))))
          (.then (fn [_] (is (= "Hello" @*text)) (is (.querySelector element ".upload-chip"))))
          (.catch #(is false (str %)))
          (.finally (fn [] (when @*root (support/unmount! @*root)) (.remove element) (done)))))))

(deftest rich-upload-retains-its-native-anchor-and-completes-once-under-strict-mode
  (async done
    (let [before @rfdb/app-db
          original client/authenticated-request!
          *calls (atom [])
          *root (atom nil)
          *media-node (atom nil)
          *stage (atom "rich mount")
          owner "rich-native-test"
          element (.createElement js/document "div")]
      (.appendChild (.-body js/document) element)
      (set! client/authenticated-request!
            (fn [& [_ _ _ success failure _]]
              (swap! *calls conj {:success success, :failure failure})))
      (rf/dispatch-sync [:form-field [:post-blog :text] "Hello end"])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [:> rf/strict-mode [editor/<markdown-editor> {:owner owner}]]))
              (await! (support/wait-for! #(= "Hello end" (some-> (.querySelector element ".md-editor-content p") .-textContent))))
              (let [editable (.querySelector element ".md-editor-content")
                    text-node (.. editable (querySelector "p [data-lexical-text=true]") -firstChild)
                    range (.createRange js/document)
                    selection (.getSelection js/window)]
                (.focus editable)
                (.setStart range text-node 5)
                (.collapse range true)
                (.removeAllRanges selection)
                (.addRange selection range)
                (.dispatchEvent js/document (js/Event. "selectionchange"))
                (await! (support/settle!))
                (transfer! editable "paste" (js/File. #js ["image"] "rich.png" #js {:type "image/png"}))
                (reset! *stage "inline chip")
                (await! (support/wait-for! #(.querySelector editable ".upload-chip")))
                (reset! *media-node (.querySelector editable ".md-media-node"))
                (reset! *stage "native anchor")
                (await! (support/wait-for! #(= 5 (:position (first (vals (get-in @rfdb/app-db [:state :blog :image-uploads])))))))
                (is (= "Hello end" (get-in @rfdb/app-db [:state :form-field :post-blog :text])))
                ;; Move the real native cursor away while the upload is pending.
                (.selectNodeContents range editable)
                (.collapse range false)
                (.removeAllRanges selection)
                (.addRange selection range)
                (.dispatchEvent js/document (js/Event. "selectionchange"))
                (await! (support/settle!))
                ((:success (first @*calls)) {:id "rich-job", :status "ready", :asset asset})
                (reset! *stage "ready document commit")
                (await! (support/wait-for! #(every? :image-inserted? (vals (get-in @rfdb/app-db [:state :blog :image-uploads])))))
                (await! (support/settle!))
                (let [text (get-in @rfdb/app-db [:state :form-field :post-blog :text])]
                  (is (= "Hello![rich.png](https://media.test/image.png) end" text))
                  (is (identical? @*media-node (.querySelector editable ".md-media-node"))
                      "The placeholder and ready image share the same native media owner")
                  (is (= 1 (count (re-seq #"https://media.test/image.png" text))))
                  (is (= 1 (count @*calls)))))))
          (.catch #(is false (str % " stage=" @*stage
                                  " text=" (get-in @rfdb/app-db [:state :form-field :post-blog :text])
                                  " uploads=" (mapv (fn [item] (select-keys item [:status :position :anchor-attached? :image-inserted?]))
                                                     (vals (get-in @rfdb/app-db [:state :blog :image-uploads])))
                                  " rendered=" (.-textContent element))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (rf/dispatch-sync [:blog/image-upload-dispose owner])
                      (set! client/authenticated-request! original)
                      (restore-fixture-db! before)
                      (done)))))))

(deftest deleting-only-a-pending-native-node-cancels-it-and-starts-the-next-upload
  (async done
    (let [before @rfdb/app-db
          original client/authenticated-request!
          *calls (atom [])
          *root (atom nil)
          owner "delete-native-test"
          element (.createElement js/document "div")]
      (.appendChild (.-body js/document) element)
      (set! client/authenticated-request!
            (fn [& [_ _ _ success failure _]] (swap! *calls conj {:success success, :failure failure})))
      (rf/dispatch-sync [:form-field [:post-blog :text] "Unchanged text"])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [editor/<markdown-editor> {:owner owner}]))
              (await! (support/wait-for! #(.querySelector element ".md-editor-content p")))
              (let [editable (.querySelector element ".md-editor-content")]
                (.focus editable)
                (transfer! editable "paste" (js/File. #js ["image"] "A.png" #js {:type "image/png"}))
                (await! (support/wait-for! #(= 1 (count @*calls))))
                (transfer! editable "paste" (js/File. #js ["image"] "B.png" #js {:type "image/png"}))
                (await! (support/wait-for! #(= 2 (.-length (.querySelectorAll editable ".md-media-node")))))
                (let [node (.querySelector editable ".md-media-node")
                      native (getNearestEditorFromDOMNode node)
                      id (.getAttribute node "data-upload-id")]
                  (.update native
                    (fn []
                      (let [selection ($createNodeSelection)]
                        (.add selection (.getKey ($getNearestNodeFromDOMNode node)))
                        ($setSelection selection))) #js {:discrete true})
                  (.dispatchCommand native KEY_BACKSPACE_COMMAND (js/KeyboardEvent. "keydown" #js {:key "Backspace"}))
                  (await! (support/wait-for! #(nil? (get-in @rfdb/app-db [:state :blog :image-uploads id]))))
                  (await! (support/wait-for! #(= 2 (count @*calls))))
                  ((:success (first @*calls)) {:id "late-A", :status "ready", :asset asset})
                  (await! (support/settle!))
                  (is (= "Unchanged text" (get-in @rfdb/app-db [:state :form-field :post-blog :text])))
                  (is (= 1 (count (get-in @rfdb/app-db [:state :blog :image-uploads]))))
                  (is (not (string/includes? (get-in @rfdb/app-db [:state :form-field :post-blog :text]) (:src asset))))))))
          (.catch #(is false (str % " rendered=" (.-textContent element))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (rf/dispatch-sync [:blog/image-upload-dispose owner])
                      (set! client/authenticated-request! original)
                      (restore-fixture-db! before)
                      (done)))))))

(defc <account-editor-fixture> []
  (let [account @(rf/subscribe [:state [:active-user :id]])
        owner (editor/use-owner account)]
    [:section {:data-editor-owner owner}
     ^{:key owner} [editor/<markdown-editor> {:owner owner}]]))

(deftest switching-account-disposes-the-old-upload-owner-without-route-unmount
  (async done
    (let [before @rfdb/app-db
          original client/authenticated-request!
          *calls (atom [])
          *root (atom nil)
          element (.createElement js/document "div")]
      (.appendChild (.-body js/document) element)
      (set! client/authenticated-request!
            (fn [& [_ _ _ success failure _]] (swap! *calls conj {:success success, :failure failure})))
      (rf/dispatch-sync [:state [:active-user] {:id 101}])
      (rf/dispatch-sync [:form-field [:post-blog :text] "Draft"])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [<account-editor-fixture>]))
              (await! (support/wait-for! #(.querySelector element ".md-editor-content p")))
              (let [owner (.getAttribute (.querySelector element "[data-editor-owner]") "data-editor-owner")]
                (.focus (.querySelector element ".md-editor-content"))
                (transfer! (.querySelector element ".md-editor-content") "paste" (js/File. #js ["A"] "A.png" #js {:type "image/png"}))
                (await! (support/wait-for! #(= 1 (count @*calls))))
                (await! (support/wait-for! #(.querySelector element ".md-media-node")))
                (rf/dispatch-sync [:state [:active-user] {:id 102}])
                (await! (support/wait-for! #(not= owner (.getAttribute (.querySelector element "[data-editor-owner]") "data-editor-owner"))))
                (await! (support/wait-for! #(empty? (get-in @rfdb/app-db [:state :blog :image-uploads]))))
                (await! (support/wait-for! #(.querySelector element ".md-editor-content p")))
                (is (nil? (.querySelector element ".md-media-node")) "The previous account's native pending node is gone")
                ((:success (first @*calls)) {:id "late-account-A", :status "ready", :asset asset})
                (await! (support/settle!))
                (is (= "Draft" (get-in @rfdb/app-db [:state :form-field :post-blog :text])))
                (.focus (.querySelector element ".md-editor-content"))
                (transfer! (.querySelector element ".md-editor-content") "paste" (js/File. #js ["B"] "B.png" #js {:type "image/png"}))
                (await! (support/wait-for! #(= 2 (count @*calls))))
                (is (= 1 (count (get-in @rfdb/app-db [:state :blog :image-uploads])))))))
          (.catch #(is false (str % " rendered=" (.-textContent element))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (set! client/authenticated-request! original)
                      (restore-fixture-db! before)
                      (done)))))))

(defc <visible-preview-fixture> [owner]
  (let [text @(rf/subscribe [:form-field [:post-blog :text]])
        uploads @(rf/subscribe [:blog/state [:image-uploads]])
        hidden? @(rf/subscribe [:state [:upload-preview-hidden?]])
        assets (into {} (keep (fn [{:keys [asset]}] (when asset [(:src asset) asset]))) (vals uploads))]
    [:section {:style {:position "fixed", :top 0, :left 0, :width "24rem", :z-index 10}}
     [editor/<upload-status> {:owner owner}]
     [:div {:hidden hidden?}
      [views/<preview-blog> {:owner owner, :post {:text text, :image-assets assets}}]]]))

(deftest uploaded-image-status-exits-only-after-the-real-preview-visibly-paints
  (async done
    (let [before @rfdb/app-db
          original client/authenticated-request!
          *success (atom nil)
          *root (atom nil)
          owner "visible-preview-test"
          id "visible-upload"
          src "/img/tolgrav-square.png"
          ready-asset (assoc asset :src src :renditions
                             {:original {:src src, :width 800, :height 600, :bytes 1024, :mime "image/png"}
                              :webp [{:src "/img/tolgrav-square.webp", :width 800, :height 600, :bytes 512, :mime "image/webp"}]
                              :avif [{:src "/img/tolgrav-square.avif", :width 800, :height 600, :bytes 256, :mime "image/avif"}]})
          element (.createElement js/document "div")]
      (.appendChild (.-body js/document) element)
      (set! client/authenticated-request!
            (fn [& [_ _ _ success _ _]] (reset! *success success)))
      (rf/dispatch-sync [:state [:upload-preview-hidden?] true])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [<visible-preview-fixture> owner]))
              (rf/dispatch-sync [:blog/image-upload-enqueue
                                 [{:id id, :name "visible.png", :owner owner
                                   :file (js/File. #js ["image"] "visible.png" #js {:type "image/png"})}] 0 0])
              (await! (support/wait-for! #(some? @*success)))
              (@*success {:id "visible-job", :status "ready", :asset ready-asset})
              (await! (support/wait-for! #(.querySelector element "img")))
              ;; Native lazy images may defer the request entirely while hidden.
              (is (.querySelector element ".upload-row") "A ready but hidden preview must retain its row")
              (is (not (get-in @rfdb/app-db [:state :blog :image-uploads id :preview-ready?])))
              (let [image (.querySelector element "img")]
                (rf/dispatch-sync [:state [:upload-preview-hidden?] false])
                (await! (support/wait-for! #(get-in @rfdb/app-db [:state :blog :image-uploads id :preview-ready?])))
                (await! (support/wait-for! #(nil? (.querySelector element ".upload-row"))))
                (is (identical? image (.querySelector element "img")) "The ready acknowledgment must preserve the visible image")
                (is (pos? (.-naturalWidth image)))
                (is (nil? (.querySelector element ".upload-row"))))))
          (.catch (fn [error]
                    (let [image (.querySelector element "img")
                          bounds (when image (.getBoundingClientRect image))]
                      (is false (str error " visibility=" (.-visibilityState js/document)
                                     " image=" (when image {:src (.-currentSrc image), :complete (.-complete image)
                                                           :natural-width (.-naturalWidth image)
                                                           :rect [(.-x bounds) (.-y bounds) (.-width bounds) (.-height bounds)]})
                                     " upload=" (dissoc (get-in @rfdb/app-db [:state :blog :image-uploads id]) :file))))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (rf/dispatch-sync [:blog/image-upload-dispose owner])
                      (set! client/authenticated-request! original)
                      (restore-fixture-db! before)
                      (done)))))))

(deftest mounted-paste-polls-and-completes-with-current-draft-edits
  (async done
    (let [before @rfdb/app-db
          original client/authenticated-request!
          *calls (atom [])
          element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (set! client/authenticated-request!
            (fn [& [method uri data success failure options]]
              (swap! *calls conj {:method method, :uri uri, :data data,
                                 :success success, :failure failure, :progress (:on-upload-progress options)})))
      (-> (go-promise
            (rf/dispatch-sync [:form-field [:post-blog :text] "Hello end"])
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [editor/<markdown-editor> {:owner "editor-test", :source? true, :exit (partial editor/dispose! "editor-test")}]))
              (await! (support/wait-for! #(source-view element)))
              (let [view (source-view element)
                    textarea (.querySelector element ".cm-content")
                    file (js/File. #js ["sample"] "pasted.png" #js {:type "image/png"})]
                (.dispatch view #js {:selection #js {:anchor 5, :head 5}})
                (is (.-defaultPrevented (transfer! textarea "paste" file)))
                (await! (support/wait-for! #(first @*calls)))
                (let [{:keys [method data success progress]} (first @*calls)]
                  (is (= :post method))
                  (is (instance? js/FormData data))
                  (is (= "pasted.png" (.-name (.get data "file"))))
                  (is (= "Hello end" (source-text element)))
                  (await! (support/wait-for! #(.querySelector element ".cm-content .upload-chip")))
                  (is (.querySelector element ".cm-content .upload-chip") "Pending upload is graphical at the source anchor")
                  (progress {:loaded 40, :total 100})
                  (await! (support/wait-for! #(= "40" (some-> (.querySelector element "progress") (.getAttribute "value")))))
                  (is (= "100" (.getAttribute (.querySelector element "progress") "max")))
                  (progress {:loaded 20, :total 100})
                  (progress {:loaded js/Infinity, :total 100})
                  (await! (support/settle!))
                  (is (= "40" (.getAttribute (.querySelector element "progress") "value")))
                  (success {:id "job-first", :status "queued"}))
                (await! (support/wait-for! #(some (fn [call] (when (= :get (:method call)) call)) @*calls)))
                (let [poll (last @*calls)
                      draft (str "New intro " (source-text element) " later edit")]
                  (is (= "/api/supabase/media/job-first" (:uri poll)))
                  (is (nil? (.getAttribute (.querySelector element "progress") "value")) "Conversion is indeterminate")
                  ((:progress (first @*calls)) {:loaded 100, :total 100})
                  (await! (support/settle!))
                  (is (nil? (.getAttribute (.querySelector element "progress") "value")) "Late transfer events cannot change processing")
                  (.dispatch view #js {:changes #js [#js {:from 0, :insert "New intro "}
                                                     #js {:from 9, :insert " later edit"}]})
                  ((:success poll) {:id "job-first", :status "ready", :asset asset})
                  (await! (support/wait-for! #(.includes (source-text element) "https://media.test/image.png")))
                  (is (= "New intro Hello![pasted.png](https://media.test/image.png) end later edit"
                         (source-text element)))
                  (is (.includes (.-textContent element) "Uploaded"))
                  (is (= "ready" (.getAttribute (.querySelector element ".upload-row") "data-status")))
                  (is (nil? (.querySelector element "progress")))
                  (let [upload (first (vals (get-in @rfdb/app-db [:state :blog :image-uploads])))
                        id (:id upload)
                        attempt (:attempt upload)
                        src (get-in upload [:asset :src])]
                    (is (.querySelector element ".upload-row") "Manifest ready alone must retain success row")
                    (rf/dispatch-sync [:blog/image-preview-ready id (inc attempt) src "editor-test" 0])
                    (await! (support/settle!))
                    (is (.querySelector element ".upload-row") "Stale attempt cannot dismiss row")
                    (rf/dispatch-sync [:blog/image-preview-failed id attempt src "editor-test" 0])
                    (await! (support/wait-for! #(.querySelector element "[role=alert]")))
                    (rf/dispatch-sync [:blog/image-preview-retry id])
                    (rf/dispatch-sync [:blog/image-preview-ready id attempt src "editor-test" 0])
                    (await! (support/settle!))
                    (is (.querySelector element ".upload-row") "Old preview generation cannot dismiss retry")
                    (rf/dispatch-sync [:blog/image-preview-ready id attempt src "editor-test" 1])
                    (await! (support/wait-for! #(nil? (.querySelector element ".upload-row"))))
                    (is (nil? (.querySelector element ".upload-row")) "Actual preview acknowledgment completes the exit"))
                  (is (not (.includes (source-text element) "image-upload:")))))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (rf/dispatch-sync [:blog/image-upload-dispose "editor-test"])
                      (set! client/authenticated-request! original)
                      (restore-fixture-db! before)
                      (done)))))))

(deftest mounted-drop-retries-and-ignores-cancelled-or-previous-attempt-results
  (async done
    (let [before @rfdb/app-db
          original client/authenticated-request!
          *calls (atom [])
          *stage (atom "source mount")
          owner "drop-editor-test"
          element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (set! client/authenticated-request!
            (fn [& [method uri data success failure options]]
              (swap! *calls conj {:method method, :uri uri, :data data,
                                 :success success, :failure failure, :progress (:on-upload-progress options)})))
      (-> (go-promise
            (rf/dispatch-sync [:form-field [:post-blog :text] "Draft"])
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [editor/<markdown-editor> {:owner owner, :source? true, :exit (partial editor/dispose! owner)}]))
              (await! (support/wait-for! #(source-view element)))
              (let [view (source-view element)
                    textarea (.querySelector element ".cm-content")
                    file (js/File. #js ["sample"] "dropped.png" #js {:type "image/png"})]
                (.dispatch view #js {:selection #js {:anchor 5, :head 5}})
                (is (.-defaultPrevented (transfer! textarea "drop" file)))
                (reset! *stage "first request")
                (await! (support/wait-for! #(first @*calls)))
                ((:failure (first @*calls)) {:message "Offline"})
                (reset! *stage "failure notice")
                (await! (support/wait-for! #(.includes (.-textContent element) "Offline")))
                (is (= "failed" (.getAttribute (.querySelector element ".upload-row") "data-status")))
                (is (.querySelector element "[role=alert]"))
                (let [retry (some #(when (= "Retry" (.-textContent %)) %)
                                  (array-seq (.querySelectorAll element "button")))]
                  (.click retry))
                (reset! *stage "retry request")
                (await! (support/wait-for! #(when (= 2 (count @*calls)) true)))
                ((:progress (first @*calls)) {:loaded 90, :total 100})
                (await! (support/settle!))
                (is (nil? (.getAttribute (.querySelector element "progress") "value")) "Retry ignores previous attempt progress")
                ;; A late result from attempt one must not complete attempt two.
                ((:success (first @*calls)) {:id "old", :status "ready", :asset asset})
                (await! (support/settle!))
                (is (not (.includes (source-text element) "https://media.test/image.png")))
                (is (.querySelector element ".cm-content .upload-chip"))
                (let [remove (some #(when (= "Remove" (.-textContent %)) %)
                                   (array-seq (.querySelectorAll element "button")))]
                  (.click remove))
                (reset! *stage "cancel canonical source")
                (await! (support/wait-for! #(= "Draft" (source-text element))))
                ((:success (second @*calls)) {:id "cancelled", :status "ready", :asset asset})
                (await! (support/settle!))
                (is (= "Draft" (source-text element)))
                (await! (support/wait-for! #(nil? (.querySelector element "li"))))
                (is (nil? (.querySelector element "li")))
                ;; Ordinary text paste must retain its native editing behavior.
                (let [transfer (js/DataTransfer.)
                      event (js/Event. "paste" #js {:bubbles true, :cancelable true})]
                  (.setData transfer "text/plain" "ordinary text")
                  (js/Object.defineProperty event "clipboardData" #js {:value transfer})
                  (.dispatchEvent textarea event)
                  ;; CodeMirror handles native text paste itself, including
                  ;; preventDefault. Verify the user's actual editing result.
                  (await! (support/wait-for! #(= "Draftordinary text" (source-text element))))
                  (is (= "Draftordinary text" (source-text element)))))))
          (.catch (fn [error] (is false (str error " stage=" @*stage " calls=" (count @*calls)
                                           " text=" (source-text element)
                                           " canonical=" (get-in @rfdb/app-db [:state :form-field :post-blog :text])
                                           " rendered=" (.-innerHTML element)))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (rf/dispatch-sync [:blog/image-upload-dispose owner])
                      (set! client/authenticated-request! original)
                      (restore-fixture-db! before)
                      (done)))))))
