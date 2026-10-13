(ns tolgraven.blog-editor-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [tolgraven.react :as r]
            [tolgraven.test-support :as support]
            [tolgraven.supabase.runtime :as client]
            [tolgraven.modules.blog.subs]
            [tolgraven.events]
            [tolgraven.subs]
            [tolgraven.modules.blog-editor.model :as model]
            [tolgraven.modules.blog-editor.editor :as editor]
            [tolgraven.schema.dsl :as dsl]
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

(defn transfer! [textarea kind file]
  ;; Native File/clipboard/drop boundaries exercise the actual Reagent handlers.
  (let [transfer (js/DataTransfer.)
        event (js/Event. kind #js {:bubbles true, :cancelable true})]
    (.add (.-items transfer) file)
    (js/Object.defineProperty event (if (= kind "paste") "clipboardData" "dataTransfer")
                              #js {:value transfer})
    (.dispatchEvent textarea event)
    event))

(deftest mounted-paste-polls-and-completes-with-current-draft-edits
  (async done
    (let [before @r/*app-db
          original client/authenticated-request!
          *calls (atom [])
          element (.createElement js/document "div")
          *root (atom nil)]
      (set! client/authenticated-request!
            (fn [& [method uri data success failure]]
              (swap! *calls conj {:method method, :uri uri, :data data,
                                 :success success, :failure failure})))
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
                (let [{:keys [method data success]} (first @*calls)]
                  (is (= :post method))
                  (is (instance? js/FormData data))
                  (is (= "pasted.png" (.-name (.get data "file"))))
                  (is (.includes (.-value textarea) "<!-- image-upload:"))
                  (success {:id "job-first", :status "queued"}))
                (await! (support/wait-for! #(some (fn [call] (when (= :get (:method call)) call)) @*calls)))
                (let [poll (last @*calls)
                      draft (str "New intro " (.-value textarea) " later edit")]
                  (is (= "/api/supabase/media/job-first" (:uri poll)))
                  (r/dispatch-sync [:form-field [:post-blog :text] draft])
                  ((:success poll) {:id "job-first", :status "ready", :asset asset})
                  (await! (support/wait-for! #(.includes (.-value textarea) "https://media.test/image.png")))
                  (is (= "New intro Hello![pasted.png](https://media.test/image.png) end later edit"
                         (.-value textarea)))
                  (is (.includes (.-textContent element) "Uploaded"))
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
            (fn [& [method uri data success failure]]
              (swap! *calls conj {:method method, :uri uri, :data data,
                                 :success success, :failure failure})))
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
                (let [retry (some #(when (= "Retry" (.-textContent %)) %)
                                  (array-seq (.querySelectorAll element "button")))]
                  (.click retry))
                (await! (support/wait-for! #(when (= 2 (count @*calls)) true)))
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
