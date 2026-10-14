(ns website.content.fields
  "Lightweight editable content bindings. The lazy editor owns writes and uploads."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.macros :refer-macros [defc]])
  (:require-macros [website.build.site :refer [when-enabled]]))

(defn annotate
  ([value path mode] (annotate value path mode []))
  ([value path mode identities]
   (let [identities' (cond-> identities
                       (when-enabled :cv false
                         (and (= :cv (first path)) (map? value) (:id value)
                              (or (:category value) (and (:from value) (:what value)))))
                       (conj {:path path, :id (:id value)}))
         metadata {:content/path path, :content/mode mode, :content/identities identities'}]
     (cond
       (map? value) (with-meta (into {} (map (fn [[k v]] [k (annotate v (conj path k) mode identities')])) value) metadata)
       (vector? value) (with-meta (mapv (fn [i v] (annotate v (conj path i) mode identities')) (range) value) metadata)
       :else value))))

(defn path [content relative]
  (when-not (contains? (:content/overrides (meta content)) (first relative))
    (if (and (= [:src] relative) (contains? (meta content) :content/image-path))
      (:content/image-path (meta content))
      (when-let [base (:content/path (meta content))] (into base relative)))))

(defn image-attrs [content relative attrs]
  (with-meta attrs {:content/image-path (path content relative)
                   :content/mode (:content/mode (meta content))
                   :content/identities (:content/identities (meta content))}))

(defn overridden [content overrides]
  (with-meta (merge content overrides)
    (assoc (meta content) :content/overrides (set (keys overrides)))))

(defn editing? [content]
  (boolean (when-enabled :user nil (= :edit (:content/mode (meta content))))))

(defn busy? [editor-state]
  (boolean (or (seq (:queue editor-state))
               (when-enabled :cv nil (= :saving (get-in editor-state [:cv-entry :status])))
               (some #(#{:saving :uploading} (:status %)) (vals (:fields editor-state))))))

(defn media [content relative]
  (let [value (or (get-in content relative) {})]
    (if-let [path' (path content relative)]
      (annotate value path' (:content/mode (meta content)) (:content/identities (meta content))) value)))

(defn stop! [event] (.stopPropagation event))

(defn current-draft [candidate content]
  (when (= (or (:identities candidate) []) (or (:content/identities (meta content)) [])) candidate))

(defc <text>
  "Keep the native element in view mode; edit its field with matching typography."
  [tag content relative & [attrs line-index]]
  (let [field-path (path content relative)
        original (get-in content relative)
        candidate (when field-path @(r/subscribe [:get :state :content-editor :fields (pr-str field-path)]))
        draft (current-draft candidate content)
        moved? (and candidate (nil? draft))
        creating? (when-enabled :cv nil
                    (and (editing? content) (= :saving @(r/subscribe [:get :state :content-editor :cv-entry :status]))))
        field-value (if draft (:value draft) original)
        value (if (some? line-index)
                (nth (string/split (or field-value "") #"\n" -1) line-index "") field-value)]
    (if (and (editing? content) field-path (or (nil? original) (string? original)))
      [tag attrs
       [:span.content-edit-field
        {:on-click stop!, :on-pointer-down stop!}
        [:span.content-edit-measure {:aria-hidden true} (str value "\u200b")]
        [(if (some? line-index) :input.content-edit-input :textarea.content-edit-input)
         {:aria-label (str "Edit " (pr-str field-path))
          :value (or value "")
          :disabled (or creating? moved? (= :saving (:status draft)))
          :title (when moved? "Discard the moved entry's retained draft before editing this box.")
          :max-length 20000
          :on-change (fn [event]
                       (let [value' (.. event -target -value)
                             value'' (if (some? line-index)
                                       (string/join "\n" (assoc (vec (string/split (or field-value "") #"\n" -1))
                                                               line-index value')) value')]
                         ;; A controlled native field must commit before the
                         ;; next key reconciles its value/selection.
                         (r/dispatch-sync [:content-editor/change field-path original value'' (:content/identities (meta content))])))}]]]
      [tag attrs (if (some? line-index) value original)])))

(defc <image>
  "Retain current pixels and geometry, with a corner upload control in edit mode."
  [attrs picture source?]
  (let [field-path (path attrs [:src])
        editor-state (when (editing? attrs) @(r/subscribe [:get :state :content-editor]))
        busy?' (busy? editor-state)
        uploading? (= :uploading (get-in editor-state [:fields (pr-str field-path) :status]))
        compact? (#{"cv-logo-picture" "cv-group-logo"} (:class attrs))
        candidate (get-in editor-state [:fields (pr-str field-path)])
        moved? (and candidate (nil? (current-draft candidate attrs)))
        blocked? (or busy?' moved?)]
    (if (and (editing? attrs) field-path)
      [:span.content-edit-image
       {:class (str (when (re-find #"media-as-bg" (or (:class attrs) "")) "content-edit-background ")
                    (when compact? (str (:class attrs) " "))
                    (when-not source? "content-edit-empty"))}
       (when source? picture)
       [:label.content-edit-upload
        {:on-click stop!
         :on-pointer-down stop!
         :title (if moved? "Discard the moved entry's retained draft before replacing this image." "Replace image")
         :aria-disabled (boolean blocked?)}
        (if compact? (if uploading? "…" "↑") (if uploading? "Processing image…" "Replace image"))
        [:input {:type "file"
                 :disabled (boolean blocked?)
                 :accept "image/png,image/jpeg,image/webp,image/avif,image/svg+xml"
                 :aria-label (str "Replace image " (pr-str field-path))
                 :on-change (fn [event]
                              (when-let [file (aget (.. event -target -files) 0)]
                                (r/dispatch [:content-editor/upload field-path (or (:cms-src attrs) (:src attrs)) file
                                             (:content/identities (meta attrs))]))
                              (set! (.. event -target -value) ""))}]]]
      picture)))
