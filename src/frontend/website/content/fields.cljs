(ns website.content.fields
  "Lightweight editable content bindings. The lazy editor owns writes and uploads."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.macros :refer-macros [defc]]))

(defn annotate [value path mode]
  (cond
    (map? value) (with-meta (into {} (map (fn [[k v]] [k (annotate v (conj path k) mode)])) value)
                  {:content/path path, :content/mode mode})
    (vector? value) (with-meta (mapv (fn [i v] (annotate v (conj path i) mode)) (range) value)
                     {:content/path path, :content/mode mode})
    :else value))

(defn path [content relative]
  (when-let [base (:content/path (meta content))] (into base relative)))

(defn editing? [content] (= :edit (:content/mode (meta content))))

(defn media [content relative]
  (let [value (or (get-in content relative) {})]
    (if-let [path' (path content relative)]
      (annotate value path' (:content/mode (meta content))) value)))

(defn stop! [event] (.stopPropagation event))

(defc <text>
  "Keep the native element in view mode; edit its field with matching typography."
  [tag content relative & [attrs line-index]]
  (let [field-path (path content relative)
        original (get-in content relative)
        draft (when field-path @(r/subscribe [:get :state :content-editor :fields (pr-str field-path)]))
        field-value (if draft (:value draft) original)
        value (if (some? line-index)
                (nth (string/split (or field-value "") #"\n" -1) line-index "") field-value)]
    (if (and (editing? content) field-path (or (nil? original) (string? original)))
      [tag attrs
       [:span.content-edit-field
        {:on-click stop! :on-pointer-down stop!}
        [:span.content-edit-measure {:aria-hidden true} (str value "\u200b")]
        [(if (some? line-index) :input.content-edit-input :textarea.content-edit-input)
         {:aria-label (str "Edit " (pr-str field-path))
          :value (or value "")
          :disabled (= :saving (:status draft))
          :max-length 20000
          :on-change (fn [event]
                       (let [value' (.. event -target -value)
                             value'' (if (some? line-index)
                                       (string/join "\n" (assoc (vec (string/split (or field-value "") #"\n" -1))
                                                               line-index value')) value')]
                         (r/dispatch [:content-editor/change field-path original value''])))}]]]
      [tag attrs (if (some? line-index) value original)])))

(defc <image>
  "Retain current pixels and geometry, with a corner upload control in edit mode."
  [attrs picture]
  (let [field-path (path attrs [:src])]
    (if (and (editing? attrs) field-path)
      [:span.content-edit-image
       {:class (when (re-find #"media-as-bg" (or (:class attrs) "")) "content-edit-background")}
       picture
       [:label.content-edit-upload
        {:on-click stop! :on-pointer-down stop!}
        "Replace image"
        [:input {:type "file"
                 :accept "image/png,image/jpeg,image/webp,image/avif,image/svg+xml"
                 :aria-label (str "Replace image " (pr-str field-path))
                 :on-change (fn [event]
                              (when-let [file (aget (.. event -target -files) 0)]
                                (r/dispatch [:content-editor/upload field-path (or (:cms-src attrs) (:src attrs)) file]))
                              (set! (.. event -target -value) ""))}]]]
      picture)))
