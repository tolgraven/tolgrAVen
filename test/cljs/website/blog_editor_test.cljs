(ns website.blog-editor-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.test-support :as support]
            [website.supabase.runtime :as client]
            [website.modules.blog.subs]
            [website.events]
            [website.subs]
            [website.modules.blog-editor.model :as model]
            [website.modules.blog-editor.editor :as editor]
            [website.modules.blog-editor.views :as views]
            [website.schema.dsl :as dsl]
            [malli.core :as m]))

(def files [{:id "first", :name "first.png", :file :first-file}
            {:id "second", :name "second.png", :file :second-file}])

(deftest attachments-preserve-cursor-order-and-concurrent-draft-edits
  (let [{:keys [text uploads]} (model/insert-files "before SELECTED after" 7 15 {} files)
        first-upload (uploads "first")
        second-upload (uploads "second")
        text' (str "New intro " text "\nAnother edit")
        second-ready (model/complete-upload text' second-upload {:src "https://media.test/second.png"})
        both-ready (model/complete-upload second-ready first-upload {:src "https://media.test/first.png"})]
    (is (m/validate (dsl/->malli model/uploads-schema) uploads))
    (is (= "before <!-- image-upload:first -->\n<!-- image-upload:second --> after" text))
    (is (= "first" (:id (model/next-upload uploads))))
    (is (nil? (model/next-upload (assoc-in uploads ["first" :status] :processing))))
    (is (= "New intro before ![first.png](https://media.test/first.png)\n![second.png](https://media.test/second.png) after\nAnother edit" both-ready))
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
    (is (= edited (model/complete-upload edited upload {:src "https://media.test/late.png"})))
    (is (model/unresolved? text {}))
    (is (= "first" (:id (model/next-upload (assoc-in uploads ["first" :status] :queued)))))))

(deftest markdown-escapes-filenames-and-inserts-literal-dollar-text
  (let [upload {:name "a[bracket]\\file$&.png", :placeholder (model/placeholder "first")}
        output (model/complete-upload (:placeholder upload) upload {:src "https://media.test/image.png"})]
    (is (= "![a\\[bracket\\]\\\\file$&.png](https://media.test/image.png)" output))
    (is (= "![line break.png](https://media.test/image.png)"
           (model/image-markdown "line\nbreak.png" "https://media.test/image.png")))))

(deftest file-boundary-accepts-images-and-preserves-ordinary-paste-drop
  (let [png (js/File. #js ["sample"] "photo.png" #js {:type "image/png"})
        avif (js/File. #js ["sample"] "PHOTO.AVIF" #js {:type ""})
        text (js/File. #js ["sample"] "notes.txt" #js {:type "text/plain"})]
    (is (editor/image-file? png))
    (is (editor/image-file? avif))
    (is (not (editor/image-file? text)))
    (is (= [png avif] (editor/image-files #js [png text avif])))))

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

(defc <visible-preview-fixture> [owner]
  (let [text @(r/subscribe [:form-field [:post-blog :text]])
        uploads @(r/subscribe [:blog/state [:image-uploads]])
        hidden? @(r/subscribe [:state [:upload-preview-hidden?]])
        assets (into {} (keep (fn [{:keys [asset]}] (when asset [(:src asset) asset]))) (vals uploads))]
    [:section {:style {:position "fixed", :top 0, :left 0, :width "24rem", :z-index 10}}
     [editor/<upload-status> {:owner owner}]
     [:div {:hidden hidden?}
      [views/<preview-blog> {:owner owner, :post {:text text, :image-assets assets}}]]]))

(deftest uploaded-image-status-exits-only-after-the-real-preview-visibly-paints
  (async done
    (let [before @r/*app-db
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
      (r/dispatch-sync [:state [:upload-preview-hidden?] true])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [<visible-preview-fixture> owner]))
              (r/dispatch-sync [:blog/image-upload-enqueue
                                 [{:id id, :name "visible.png", :owner owner
                                   :file (js/File. #js ["image"] "visible.png" #js {:type "image/png"})}] 0 0])
              (await! (support/wait-for! #(some? @*success)))
              (@*success {:id "visible-job", :status "ready", :asset ready-asset})
              (await! (support/wait-for! #(.querySelector element "img")))
              ;; Native lazy images may defer the request entirely while hidden.
              (is (.querySelector element ".upload-row") "A ready but hidden preview must retain its row")
              (is (not (get-in @r/*app-db [:state :blog :image-uploads id :preview-ready?])))
              (let [image (.querySelector element "img")]
                (r/dispatch-sync [:state [:upload-preview-hidden?] false])
                (await! (support/wait-for! #(get-in @r/*app-db [:state :blog :image-uploads id :preview-ready?])))
                (await! (support/wait-for! #(nil? (.querySelector element ".upload-row"))))
                (is (identical? image (.querySelector element "img")) "The ready acknowledgment must preserve the visible image")
                (is (pos? (.-naturalWidth image)))
                (is (nil? (.querySelector element ".upload-row"))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (r/dispatch-sync [:blog/image-upload-dispose owner])
                      (set! client/authenticated-request! original)
                      (reset! r/*app-db before)
                      (done)))))))

(deftest mounted-paste-polls-and-completes-with-current-draft-edits
  (async done
    (let [before @r/*app-db
          original client/authenticated-request!
          *calls (atom [])
          element (.createElement js/document "div")
          *root (atom nil)]
      (set! client/authenticated-request!
            (fn [& [method uri data success failure options]]
              (swap! *calls conj {:method method, :uri uri, :data data,
                                 :success success, :failure failure, :progress (:on-upload-progress options)})))
      (-> (go-promise
            (r/dispatch-sync [:form-field [:post-blog :text] "Hello end"])
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [editor/<source-editor> {:owner "editor-test", :exit (partial editor/dispose! "editor-test")}]))
              (let [textarea (.querySelector element "textarea")
                    file (js/File. #js ["sample"] "pasted.png" #js {:type "image/png"})]
                (.setSelectionRange textarea 5 5)
                (is (.-defaultPrevented (transfer! textarea "paste" file)))
                (await! (support/wait-for! #(first @*calls)))
                (let [{:keys [method data success progress]} (first @*calls)]
                  (is (= :post method))
                  (is (instance? js/FormData data))
                  (is (= "pasted.png" (.-name (.get data "file"))))
                  (is (.includes (.-value textarea) "<!-- image-upload:"))
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
                      draft (str "New intro " (.-value textarea) " later edit")]
                  (is (= "/api/supabase/media/job-first" (:uri poll)))
                  (is (nil? (.getAttribute (.querySelector element "progress") "value")) "Conversion is indeterminate")
                  ((:progress (first @*calls)) {:loaded 100, :total 100})
                  (await! (support/settle!))
                  (is (nil? (.getAttribute (.querySelector element "progress") "value")) "Late transfer events cannot change processing")
                  (r/dispatch-sync [:form-field [:post-blog :text] draft])
                  ((:success poll) {:id "job-first", :status "ready", :asset asset})
                  (await! (support/wait-for! #(.includes (.-value textarea) "https://media.test/image.png")))
                  (is (= "New intro Hello![pasted.png](https://media.test/image.png) end later edit"
                         (.-value textarea)))
                  (is (.includes (.-textContent element) "Uploaded"))
                  (is (= "ready" (.getAttribute (.querySelector element ".upload-row") "data-status")))
                  (is (nil? (.querySelector element "progress")))
                  (let [upload (first (vals (get-in @r/*app-db [:state :blog :image-uploads])))
                        id (:id upload)
                        attempt (:attempt upload)
                        src (get-in upload [:asset :src])]
                    (is (.querySelector element ".upload-row") "Manifest ready alone must retain success row")
                    (r/dispatch-sync [:blog/image-preview-ready id (inc attempt) src "editor-test" 0])
                    (await! (support/settle!))
                    (is (.querySelector element ".upload-row") "Stale attempt cannot dismiss row")
                    (r/dispatch-sync [:blog/image-preview-failed id attempt src "editor-test" 0])
                    (await! (support/wait-for! #(.querySelector element "[role=alert]")))
                    (r/dispatch-sync [:blog/image-preview-retry id])
                    (r/dispatch-sync [:blog/image-preview-ready id attempt src "editor-test" 0])
                    (await! (support/settle!))
                    (is (.querySelector element ".upload-row") "Old preview generation cannot dismiss retry")
                    (r/dispatch-sync [:blog/image-preview-ready id attempt src "editor-test" 1])
                    (await! (support/wait-for! #(nil? (.querySelector element ".upload-row"))))
                    (is (nil? (.querySelector element ".upload-row")) "Actual preview acknowledgment completes the exit"))
                  (is (not (.includes (.-value textarea) "image-upload:")))))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (r/dispatch-sync [:blog/image-upload-dispose "editor-test"])
                      (set! client/authenticated-request! original)
                      (reset! r/*app-db before)
                      (done)))))))

(deftest mounted-drop-retries-and-ignores-cancelled-or-previous-attempt-results
  (async done
    (let [before @r/*app-db
          original client/authenticated-request!
          *calls (atom [])
          element (.createElement js/document "div")
          *root (atom nil)]
      (set! client/authenticated-request!
            (fn [& [method uri data success failure options]]
              (swap! *calls conj {:method method, :uri uri, :data data,
                                 :success success, :failure failure, :progress (:on-upload-progress options)})))
      (-> (go-promise
            (r/dispatch-sync [:form-field [:post-blog :text] "Draft"])
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [editor/<source-editor> {:owner "editor-test", :exit (partial editor/dispose! "editor-test")}]))
              (let [textarea (.querySelector element "textarea")
                    file (js/File. #js ["sample"] "dropped.png" #js {:type "image/png"})]
                (.setSelectionRange textarea 5 5)
                (is (.-defaultPrevented (transfer! textarea "drop" file)))
                (await! (support/wait-for! #(first @*calls)))
                ((:failure (first @*calls)) {:message "Offline"})
                (await! (support/wait-for! #(.includes (.-textContent element) "Offline")))
                (is (= "failed" (.getAttribute (.querySelector element ".upload-row") "data-status")))
                (is (.querySelector element "[role=alert]"))
                (let [retry (some #(when (= "Retry" (.-textContent %)) %)
                                  (array-seq (.querySelectorAll element "button")))]
                  (.click retry))
                (await! (support/wait-for! #(when (= 2 (count @*calls)) true)))
                ((:progress (first @*calls)) {:loaded 90, :total 100})
                (await! (support/settle!))
                (is (nil? (.getAttribute (.querySelector element "progress") "value")) "Retry ignores previous attempt progress")
                ;; A late result from attempt one must not complete attempt two.
                ((:success (first @*calls)) {:id "old", :status "ready", :asset asset})
                (await! (support/settle!))
                (is (not (.includes (.-value textarea) "https://media.test/image.png")))
                (is (.includes (.-value textarea) "image-upload:"))
                (let [remove (some #(when (= "Remove" (.-textContent %)) %)
                                   (array-seq (.querySelectorAll element "button")))]
                  (.click remove))
                (await! (support/wait-for! #(= "Draft" (.-value textarea))))
                ((:success (second @*calls)) {:id "cancelled", :status "ready", :asset asset})
                (await! (support/settle!))
                (is (= "Draft" (.-value textarea)))
                (await! (support/wait-for! #(nil? (.querySelector element "li"))))
                (is (nil? (.querySelector element "li")))
                ;; Ordinary text paste must retain its native editing behavior.
                (let [transfer (js/DataTransfer.)
                      event (js/Event. "paste" #js {:bubbles true, :cancelable true})]
                  (.setData transfer "text/plain" "ordinary text")
                  (js/Object.defineProperty event "clipboardData" #js {:value transfer})
                  (.dispatchEvent textarea event)
                  (is (not (.-defaultPrevented event)))))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (r/dispatch-sync [:blog/image-upload-dispose "editor-test"])
                      (set! client/authenticated-request! original)
                      (reset! r/*app-db before)
                      (done)))))))
