(ns tolgraven.modules.blog-editor.editor
  "Markdown draft attachment UI; re-frame owns upload state and draft text."
  (:require [clojure.string :as string]
            [tolgraven.react :as r]
            [tolgraven.macros :as m :refer-macros [defc]]
            [tolgraven.modules.blog-editor.upload-bridge :as bridge]
            [tolgraven.modules.blog-editor.events]
            [tolgraven.modules.blog-editor.model :as model]))

(defn image-file? [file]
  (or (string/starts-with? (.-type file) "image/")
      (and (string/blank? (.-type file))
           (boolean (re-find #"(?i)\.(jpe?g|png|webp|avif|svg|gif|heic|heif|tiff?)$" (.-name file))))))

(defn image-files [files]
  (filterv image-file? (array-seq files)))

(defn- enqueue! [files textarea owner]
  (when (seq files)
    (r/dispatch [:blog/image-upload-enqueue
                  (mapv (fn [file] {:id (str (random-uuid)), :name (.-name file), :file file, :owner owner}) files)
                  (or (some-> textarea .-selectionStart) 0)
                  (or (some-> textarea .-selectionEnd) 0)])))

(defn- transfer-images! [event textarea transfer owner]
  (let [files (image-files (.-files transfer))]
    (when (seq files)
      (.preventDefault event)
      (enqueue! files textarea owner))))

(defn dispose! [owner _] (r/dispatch [:blog/image-upload-dispose owner]))

(defc <source-editor>
  {:features [:lifecycle]}
  [{:keys [owner]}]
  :let [*textarea (atom nil)
        *picker-generation (r/atom 0)
        textarea-ref (fn [element] (reset! *textarea element) nil)]
  (let [text (or @(r/subscribe [:form-field [:post-blog :text]]) "")
          uploads (or @(r/subscribe [:blog/state [:image-uploads]]) {})]
      [:div.blog-image-editor
       [:label {:for "blog-markdown-text"} "Text (Markdown)"]
       [:textarea.form-control
        {:id "blog-markdown-text"
         :rows 6
         :value text
         :ref textarea-ref
         :placeholder "Write Markdown. Paste or drop images to attach them."
         :on-change #(r/dispatch-sync [:form-field [:post-blog :text] (.. % -target -value)])
         :on-blur #(r/dispatch [:form-field [:post-blog :text] (.. % -target -value) :blur])
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
               [:button {:type "button" :on-click #(r/dispatch [:blog/image-upload-retry id])} "Retry"])
             (when (not= :ready status)
               [:button {:type "button" :on-click #(r/dispatch [:blog/image-upload-cancel id])} "Remove"])])])
       (when (model/unresolved? text uploads)
         [:p {:role "status"} "Finish or remove image uploads before publishing."])]))

(defc <upload-status> []
  (let [uploads @(r/subscribe [:blog/state [:image-uploads]])]
    [:ul.blog-image-editor__uploads {:aria-live "polite"}
     (for [{:keys [id name status error]} (sort-by :order (vals uploads))]
       ^{:key id}
       [:li [:span name " — " (case status :ready "Uploaded" :failed error :queued "Waiting…" :uploading "Uploading…" :processing "Preparing image sizes…")]
        (when (= status :failed)
          [:button {:type "button", :on-click #(r/dispatch [:blog/image-upload-retry id])} "Retry"])
        (when (not= status :ready)
          [:button {:type "button", :on-click #(r/dispatch [:blog/image-upload-cancel id])} "Remove"])])]))

(defc <library-state> []
  (let [{:keys [pending? error next-offset]} @(r/subscribe [:state [:blog-editor :library]])]
    [:div
     (when pending? [:p {:role "status"} "Loading images…"])
     (when error [:p {:role "alert"} error
                  [:button {:type "button", :on-click #(r/dispatch [:blog-editor/library-load 0])} "Retry"]])
     (when next-offset
       [:button {:type "button", :disabled pending?, :on-click #(r/dispatch [:blog-editor/library-load next-offset])} "More images"])]))

(defc <markdown-editor>
  "Canonical form text and upload jobs stay in the posting owner; only the SDK is lazy."
  {:features [:lifecycle]}
  [{:keys [owner]}]
  :let [*rich? (r/atom true)]
  (let [text (or @(r/subscribe [:form-field [:post-blog :text]]) "")
        uploads @(r/subscribe [:blog/state [:image-uploads]])
        library @(r/subscribe [:state [:blog-editor :library]])
        assets (->> (concat (:assets library) (keep :asset (vals uploads)))
                    (reduce (fn [[seen result] asset]
                              (if (seen (:id asset)) [seen result]
                                  [(conj seen (:id asset)) (conj result asset)])) [#{} []])
                    second)
        upload! (r/use-callback #(bridge/upload! owner %) [owner])
        library-action (r/use-callback (fn [action offset]
                                        (case action
                                          :load (r/dispatch [:blog-editor/library-load offset])
                                          :view [<library-state>])) [])]
    [:section.blog-image-editor
     [:nav {:aria-label "Markdown editor mode"}
      [:button {:type "button", :aria-pressed @*rich?, :on-click #(reset! *rich? true)} "Visual editor"]
      [:button {:type "button", :aria-pressed (not @*rich?), :on-click #(reset! *rich? false)} "Plain Markdown"]]
     (if @*rich?
       (m/<> :markdown-editor/editor
             {:text text
              :on-change #(r/dispatch-sync [:form-field [:post-blog :text] %])
              :upload! upload!
              :assets assets
              :image-urls (mapv :src assets)
              :library library-action})
       [<source-editor> {:owner owner}])
     (when @*rich? [<upload-status>])]))
