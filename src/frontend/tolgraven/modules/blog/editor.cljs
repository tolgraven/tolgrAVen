(ns tolgraven.modules.blog.editor
  "Markdown draft attachment UI; re-frame owns upload state and draft text."
  (:require [clojure.string :as string]
            [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.modules.blog.editor-events]
            [tolgraven.modules.blog.editor-model :as model]))

(defn image-file? [file]
  (or (string/starts-with? (.-type file) "image/")
      (and (string/blank? (.-type file))
           (boolean (re-find #"(?i)\.(jpe?g|png|webp|avif|gif|heic|heif|tiff?)$" (.-name file))))))

(defn image-files [files]
  (filterv image-file? (array-seq files)))

(defn- enqueue! [files textarea owner]
  (when (seq files)
    (rf/dispatch [:blog/image-upload-enqueue
                  (mapv (fn [file] {:id (str (random-uuid)), :name (.-name file), :file file, :owner owner}) files)
                  (or (some-> textarea .-selectionStart) 0)
                  (or (some-> textarea .-selectionEnd) 0)])))

(defn- transfer-images! [event textarea transfer owner]
  (let [files (image-files (.-files transfer))]
    (when (seq files)
      (.preventDefault event)
      (enqueue! files textarea owner))))

(defn dispose! [owner _] (rf/dispatch [:blog/image-upload-dispose owner]))

(defc <markdown-editor>
  {:features [:lifecycle]}
  [{:keys [owner]}]
  (r/with-let [*textarea (atom nil)
               *picker-generation (r/atom 0)
               textarea-ref (fn [element] (reset! *textarea element) nil)]
    (let [text (or @(rf/subscribe [:form-field [:post-blog :text]]) "")
          uploads (or @(rf/subscribe [:blog/state [:image-uploads]]) {})]
      [:div.blog-image-editor
       [:label {:for "blog-markdown-text"} "Text (Markdown)"]
       [:textarea.form-control
        {:id "blog-markdown-text"
         :rows 6
         :value text
         :ref textarea-ref
         :placeholder "Write Markdown. Paste or drop images to attach them."
         :on-change #(rf/dispatch-sync [:form-field [:post-blog :text] (.. % -target -value)])
         :on-blur #(rf/dispatch [:form-field [:post-blog :text] (.. % -target -value) :blur])
         :on-paste #(transfer-images! % @*textarea (.-clipboardData %) owner)
         :on-drop #(transfer-images! % @*textarea (.-dataTransfer %) owner)
         :on-drag-over (fn [event]
                         (when (some #(= "file" (.-kind %))
                                     (array-seq (.. event -dataTransfer -items)))
                           (.preventDefault event))) }]
       [:label.blog-image-editor__attach
        "Attach images "
        ^{:key @*picker-generation}
        [:input {:type "file"
                 :accept "image/*"
                 :multiple true
                 :on-change (fn [event]
                              (enqueue! (image-files (.. event -target -files)) @*textarea owner)
                              (swap! *picker-generation inc))}]]
       [:p.blog-image-editor__hint "Paste, drop, or select images. Uploads are inserted where your cursor is."]
       (when (seq uploads)
         [:ul.blog-image-editor__uploads {:aria-live "polite"}
          (for [{:keys [id name status error]} (sort-by :order (vals uploads))]
            ^{:key id}
            [:li
             [:span name " — " (case status
                                  :ready "Uploaded"
                                  :failed (or error "Upload failed")
                                  :queued "Waiting to upload…"
                                  :uploading "Uploading…"
                                  :processing "Preparing image sizes…")]
             (when (= :failed status)
               [:button {:type "button" :on-click #(rf/dispatch [:blog/image-upload-retry id])} "Retry"])
             (when (not= :ready status)
               [:button {:type "button" :on-click #(rf/dispatch [:blog/image-upload-cancel id])} "Remove"])])])
       (when (model/unresolved? text uploads)
         [:p {:role "status"} "Finish or remove image uploads before publishing."])])))
