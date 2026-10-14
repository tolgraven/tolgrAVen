(ns website.modules.blog-editor.editor
  "Markdown draft attachment UI; re-frame owns upload state and draft text."
  (:require [website.react :as r]
            [website.macros :as m :refer-macros [defc]]
            [website.modules.blog-editor.upload-bridge :as bridge]
            [website.modules.blog-editor.events]
            [website.modules.blog-editor.model :as model]
            [website.modules.blog-editor.files :as files]
            [website.modules.markdown-editor.features :as features]
            [website.components.content.upload :as upload]))

(defn- enqueue! [files textarea owner]
  (when (seq files)
    (r/dispatch [:blog/image-upload-enqueue
                  (mapv (fn [file] {:id (str (random-uuid)), :name (.-name file), :file file, :owner owner}) files)
                  (or (some-> textarea .-selectionStart) 0)
                  (or (some-> textarea .-selectionEnd) 0)])))

(defn- transfer-images! [event textarea transfer owner]
  (let [files (files/image-files (.-files transfer))]
    (when (seq files)
      (.preventDefault event)
      (enqueue! files textarea owner))))

(defn dispose! [owner _] (r/dispatch [:blog/image-upload-dispose owner]))

(defn use-owner [account]
  ;; The transient SDK/transport lifetime changes with authentication, while
  ;; the persistent draft document has its own restored identity.
  (r/use-memo #(str (random-uuid)) [account]))

(defn upload-actions [owner {:keys [id attempt preview-attempt preview-error] :as item}]
  (cond-> (assoc item
                 :retry! #(r/dispatch [(if preview-error :blog/image-preview-retry :blog/image-upload-retry) id])
                 :remove! #(r/dispatch [:blog/image-upload-cancel id])
                 :exited! #(when attempt (r/dispatch [:blog/image-chip-exited owner id attempt (or preview-attempt 0)])))
    preview-error (assoc :status :failed, :error preview-error)))

(defn enqueue-files! [owner files start end sdk?]
  (r/dispatch-sync [:blog/image-upload-enqueue
                    (mapv (fn [file]
                            (assoc (if (map? file) file {:id (str (random-uuid)), :name (.-name file), :file file})
                                   :owner owner, :sdk? sdk?)) files) start end]))

(defc <upload-status>
  {:features [:presence]}
  [{:keys [owner]}]
  (let [uploads @(r/subscribe [:blog/state [:image-uploads]])]
    [:ul.blog-image-editor__uploads
     (for [{:keys [id preview-ready? preview-error status] :as item} (sort-by :order (vals uploads))
           :when (and (= owner (:owner item)) (not (:cancelled? item)) (not (and (= :ready status) preview-ready?)))]
       ^{:key id} [upload/<upload-row> (cond-> (assoc item
                                                   :exit {:class "upload-row--exit"}
                                                   :retry! #(r/dispatch [(if preview-error :blog/image-preview-retry :blog/image-upload-retry) id])
                                                   :remove! #(r/dispatch [:blog/image-upload-cancel id]))
                                       preview-error (assoc :status :failed, :error preview-error))])]))

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
  {:features [:lifecycle]
   :spec true
   :spec-schema [:map
                 [:owner :string]
                 [:?form-path [:vector :any]]
                 [:?kind [:enum :post :comment]]
                 [:?enabled-features features/schema]
                 [:?library? :boolean]
                 [:?source? :boolean]
                 [:?exit fn?]]}
  [{:keys [owner source? form-path kind enabled-features library?]
    :or {form-path [:post-blog :text], kind :post}}]
  :let [*rich? (r/atom (not source?))]
  (let [text-query [:form-field form-path]
        text (or @(r/subscribe text-query) "")
        document-id (if (= :post kind)
                      @(r/subscribe [:state [:blog-editor :draft :document-id]]) owner)
        library-enabled? (and (= :post kind) (not (false? library?)))
        uploads @(r/subscribe [:blog-editor/uploads owner])
        library (when library-enabled? @(r/subscribe [:state [:blog-editor :library]]))
        assets (->> (concat (:assets library) (keep :asset uploads))
                    (reduce (fn [[seen result] asset]
                              (if (seen (:id asset)) [seen result]
                                  [(conj seen (:id asset)) (conj result asset)])) [#{} []])
                    second)
        upload! (r/use-callback #(bridge/upload! owner %) [owner])
        library-action (r/use-callback (fn [action offset]
                                        (when library-enabled?
                                          (case action
                                          :load (r/dispatch [:blog-editor/library-load offset])
                                          :view [<library-state>]))) [library-enabled?])]
    (r/use-effect
      (fn []
        (r/dispatch-sync [:blog/editor-mounted owner {:form-path form-path
                                                     :kind kind
                                                     :document-id document-id}])
        #(r/dispatch-sync [:blog/image-upload-dispose owner])) [owner form-path kind document-id])
    [:section.blog-image-editor
     [:nav {:aria-label "Markdown editor mode"}
      [:button {:type "button", :aria-pressed @*rich?, :on-click #(do (r/dispatch-sync [:blog/editor-mode owner true]) (reset! *rich? true))} "Visual editor"]
      [:button {:type "button", :aria-pressed (not @*rich?), :on-click #(do (r/dispatch-sync [:blog/editor-mode owner false]) (reset! *rich? false))} "Plain Markdown"]]
     (m/<> {:module :markdown-editor, :view :editor, :load-on :immediate}
             {:text text
              :document-id document-id
              :enabled-features (if (= :comment kind)
                                  (disj (or enabled-features features/comment-features) :site-components)
                                  (or enabled-features features/post-features))
              :source? (not @*rich?)
              :text-query text-query
              :uploads uploads
              :uploads-query [:blog-editor/uploads owner]
              :upload-query (r/use-callback (fn [id] [:blog/state [:image-uploads id]]) [])
              :enqueue! (r/use-callback (partial enqueue-files! owner) [owner])
              :render-upload (r/use-callback (fn [item] [upload/<upload-slot> (upload-actions owner item)]) [owner])
              :insert-ready! (r/use-callback (fn [item insert!]
                                              (r/dispatch-sync [:blog/image-upload-insert owner (:id item) (:attempt item)
                                                                (get-in item [:asset :src]) insert!])) [owner])
              :on-change (r/use-callback (fn [text & [changes positions]]
                                           ;; Literal native input must be accepted before the
                                           ;; next key transaction; queued echoes can otherwise
                                           ;; overwrite newer text during controlled reconciliation.
                                           ((if @*rich? r/dispatch r/dispatch-sync)
                                            [:blog/editor-text-changed owner text changes positions document-id])) [owner document-id])
              :on-anchors (r/use-callback #(r/dispatch [:blog/editor-anchors owner %]) [owner])
              :on-removed (r/use-callback #(r/dispatch [:blog/image-anchor-removed owner %]) [owner])
              :upload! upload!
              :assets assets
              :image-urls (mapv :src assets)
              :library (when library-enabled? library-action)})
     [<upload-status> {:owner owner}]]))
